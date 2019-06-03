/*
 * Copyright (c) 2018. Atguigu Inc. All Rights Reserved.
 */

package com.atguigu.product

import java.util.UUID

import com.atguigu.commons.conf.ConfigurationManager
import com.atguigu.commons.constant.Constants
import com.atguigu.commons.utils.ParamUtils
import net.sf.json.JSONObject
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}

import scala.util.Random

/**
  *
  */
object AreaTop3ProductApp {

  def main(args: Array[String]): Unit = {

    // 获取统计任务参数【为了方便，直接从配置文件中获取，企业中会从一个调度平台获取】
    val jsonStr = ConfigurationManager.config.getString(Constants.TASK_PARAMS)
    val taskParam = JSONObject.fromObject(jsonStr)

    // 任务的执行ID，用户唯一标示运行后的结果，用在MySQL数据库中
    val taskUUID = UUID.randomUUID().toString

    // 构建Spark上下文
    val sparkConf = new SparkConf().setAppName("SessionAnalyzer").setMaster("local[*]")

    // 创建Spark客户端
    val spark = SparkSession.builder().config(sparkConf).enableHiveSupport().getOrCreate()
    val sc = spark.sparkContext

    // 注册自定义函数
    spark.udf.register("concat_long_string", (v1: Long, v2: String, split: String) => v1.toString + split + v2)
    spark.udf.register("get_json_object", (json: String, field: String) => {
      val jsonObject = JSONObject.fromObject(json);
      jsonObject.getString(field)
    })
    spark.udf.register("group_concat_distinct", new GroupConcatDistinctUDAF())

    // 获取任务日期参数
    val startDate = ParamUtils.getParam(taskParam, Constants.PARAM_START_DATE)
    val endDate = ParamUtils.getParam(taskParam, Constants.PARAM_END_DATE)

    // 查询用户指定日期范围内的点击行为数据（city_id，在哪个城市发生的点击行为）
    val cityid2clickActionRDD = getcityid2ClickActionRDDByDate(spark, startDate, endDate)

    // 查询城市信息
    // 使用(city_id , 城市信息)
    val cityid2cityInfoRDD = getcityid2CityInfoRDD(spark)

    // 生成点击商品基础信息临时表
    // 将点击行为cityid2clickActionRDD和城市信息cityid2cityInfoRDD进行Join关联
    // tmp_click_product_basic
    generateTempClickProductBasicTable(spark, cityid2clickActionRDD, cityid2cityInfoRDD)

    // 生成各区域各商品点击次数的临时表
    // 对tmp_click_product_basic表中的数据进行count聚合统计，得到点击次数
    // tmp_area_product_click_count
    generateTempAreaPrdocutClickCountTable(spark)

    // 生成包含完整商品信息的各区域各商品点击次数的临时表
    // 关联tmp_area_product_click_count表与product_info表，在tmp_area_product_click_count基础上引入商品的详细信息
    generateTempAreaFullProductClickCountTable(spark)

    // 需求一：使用开窗函数获取各个区域内点击次数排名前3的热门商品
    val areaTop3ProductRDD = getAreaTop3ProductRDD(taskUUID, spark)

    // 将数据转换为DF，并保存到MySQL数据库
    import spark.implicits._
    val areaTop3ProductDF = areaTop3ProductRDD.rdd.map(row =>
      AreaTop3Product(taskUUID, row.getAs[String]("area"), row.getAs[String]("area_level"), row.getAs[Long]("product_id"), row.getAs[String]("city_infos"), row.getAs[Long]("click_count"), row.getAs[String]("product_name"), row.getAs[String]("product_status"))
    ).toDS

    areaTop3ProductDF.write
      .format("jdbc")
      .option("url", ConfigurationManager.config.getString(Constants.JDBC_URL))
      .option("dbtable", "area_top3_product")
      .option("user", ConfigurationManager.config.getString(Constants.JDBC_USER))
      .option("password", ConfigurationManager.config.getString(Constants.JDBC_PASSWORD))
      .mode(SaveMode.Append)
      .save()

    spark.close()
  }

  /**
    * 需求一：获取各区域top3热门商品
    * 使用开窗函数先进行一个子查询,按照area进行分组，给每个分组内的数据，按照点击次数降序排序，打上一个组内的行号
    * 接着在外层查询中，过滤出各个组内的行号排名前3的数据
    *
    * @return
    */
  def getAreaTop3ProductRDD(taskid: String, spark: SparkSession): DataFrame = {

    // 华北、华东、华南、华中、西北、西南、东北
    // A级：华北、华东
    // B级：华南、华中
    // C级：西北、西南
    // D级：东北

    // case when
    // 根据多个条件，不同的条件对应不同的值
    // case when then ... when then ... else ... end

    val sql = "SELECT " +
        "area," +
        "CASE " +
          "WHEN area='China North' OR area='China East' THEN 'A Level' " +
          "WHEN area='China South' OR area='China Middle' THEN 'B Level' " +
          "WHEN area='West North' OR area='West South' THEN 'C Level' " +
          "ELSE 'D Level' " +
        "END area_level," +
        "product_id," +
        "city_infos," +
        "click_count," +
        "product_name," +
        "product_status " +
      "FROM (" +
        "SELECT " +
          "area," +
          "product_id," +
          "click_count," +
          "city_infos," +
          "product_name," +
          "product_status," +
          "row_number() OVER (PARTITION BY area ORDER BY click_count DESC) rank " +
        "FROM tmp_area_fullprod_click_count " +
        ") t " +
      "WHERE rank<=3"

    spark.sql(sql)
  }

  /**
    * 生成区域商品点击次数临时表（包含了商品的完整信息）
    *
    * @param spark
    */
  def generateTempAreaFullProductClickCountTable(spark: SparkSession) {

    // 将之前得到的各区域各商品点击次数表，product_id
    // 去关联商品信息表，product_id，product_name和product_status
    // product_status要特殊处理，0，1，分别代表了自营和第三方的商品，放在了一个json串里面
    // get_json_object()函数，可以从json串中获取指定的字段的值
    // if()函数，判断，如果product_status是0，那么就是自营商品；如果是1，那么就是第三方商品
    // area, product_id, click_count, city_infos, product_name, product_status

    // 你拿到到了某个区域top3热门的商品，那么其实这个商品是自营的，还是第三方的
    // 其实是很重要的一件事

    // 技术点：内置if函数的使用

    val sql = "SELECT " +
        "tapcc.area," +
        "tapcc.product_id," +
        "tapcc.click_count," +
        "tapcc.city_infos," +
        "pi.product_name," +
        "if(get_json_object(pi.extend_info,'product_status')='0','Self','Third Party') product_status " +
      "FROM tmp_area_product_click_count tapcc " +
        "JOIN product_info pi ON tapcc.product_id=pi.product_id "

    val df = spark.sql(sql)

    df.createOrReplaceTempView("tmp_area_fullprod_click_count")
  }

  /**
    * 生成各区域各商品点击次数临时表
    *
    * @param spark
    */
  def generateTempAreaPrdocutClickCountTable(spark: SparkSession) {

    // 按照area和product_id两个字段进行分组
    // 计算出各区域各商品的点击次数
    // 可以获取到每个area下的每个product_id的城市信息拼接起来的串
    val sql = "SELECT " +
        "area," +
        "product_id," +
        "count(*) click_count, " +
        "group_concat_distinct(concat_long_string(city_id,city_name,':')) city_infos " +
      "FROM tmp_click_product_basic " +
      "GROUP BY area,product_id "

    val df = spark.sql(sql)

    // 各区域各商品的点击次数（以及额外的城市列表）,再次将查询出来的数据注册为一个临时表
    df.createOrReplaceTempView("tmp_area_product_click_count")
  }

  /**
    * 生成点击商品基础信息临时表
    *
    * @param cityid2clickActionRDD
    * @param cityid2cityInfoRDD
    */
  def generateTempClickProductBasicTable(spark: SparkSession, cityid2clickActionRDD: RDD[(Long, Row)], cityid2cityInfoRDD: RDD[(Long, Row)]) {
    // 执行join操作，进行点击行为数据和城市数据的关联
    val joinedRDD = cityid2clickActionRDD.join(cityid2cityInfoRDD)

    // 将上面的JavaPairRDD，转换成一个JavaRDD<Row>（才能将RDD转换为DataFrame）
    val mappedRDD = joinedRDD.map { case (cityid, (action, cityinfo)) =>
      val productid = action.getLong(1)
      val cityName = cityinfo.getString(1)
      val area = cityinfo.getString(2)
      (cityid, cityName, area, productid)
    }
    // 1 北京
    // 2 上海
    // 1 北京
    // group by area,product_id
    // 1:北京,2:上海

    // 两个函数
    // UDF：concat2()，将两个字段拼接起来，用指定的分隔符
    // UDAF：group_concat_distinct()，将一个分组中的多个字段值，用逗号拼接起来，同时进行去重
    import spark.implicits._
    val df = mappedRDD.toDF("city_id", "city_name", "area", "product_id")
    // 为df创建临时表
    df.createOrReplaceTempView("tmp_click_product_basic")
  }

  /**
    * 使用Spark SQL从MySQL中查询城市信息
    *
    * @return
    */
  def getcityid2CityInfoRDD(spark: SparkSession): RDD[(Long, Row)] = {

    val cityInfo = Array((0L, "北京", "华北"), (1L, "上海", "华东"), (2L, "南京", "华东"), (3L, "广州", "华南"), (4L, "三亚", "华南"), (5L, "武汉", "华中"), (6L, "长沙", "华中"), (7L, "西安", "西北"), (8L, "成都", "西南"), (9L, "哈尔滨", "东北"))
    import spark.implicits._
    val cityInfoDF = spark.sparkContext.makeRDD(cityInfo).toDF("city_id", "city_name", "area")
    cityInfoDF.rdd.map(item => (item.getAs[Long]("city_id"), item))
  }

  /**
    * 查询指定日期范围内的点击行为数据
    *
    * @param startDate 起始日期
    * @param endDate   截止日期
    * @return 点击行为数据
    */
  def getcityid2ClickActionRDDByDate(spark: SparkSession, startDate: String, endDate: String): RDD[(Long, Row)] = {
    // 从user_visit_action中，查询用户访问行为数据
    // 第一个限定：click_product_id，限定为不为空的访问行为，那么就代表着点击行为
    // 第二个限定：在用户指定的日期范围内的数据

    val sql =
      "SELECT " +
          "city_id," +
          "click_product_id " +
        "FROM user_visit_action " +
        "WHERE click_product_id IS NOT NULL and click_product_id != -1L " +
          "AND date>='" + startDate + "' " +
          "AND date<='" + endDate + "'"

    val clickActionDF = spark.sql(sql)

    //(cityid, row)
    clickActionDF.rdd.map(item => (item.getAs[Long]("city_id"), item))
  }

}
