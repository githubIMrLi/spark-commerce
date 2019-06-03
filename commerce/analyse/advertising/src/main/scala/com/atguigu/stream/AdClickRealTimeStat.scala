/*
 * Copyright (c) 2018. Atguigu Inc. All Rights Reserved.
 */

package com.atguigu.stream

import java.util.Date

import com.atguigu.commons.conf.ConfigurationManager
import com.atguigu.commons.utils.DateUtils
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.streaming.dstream.{DStream, InputDStream}
import org.apache.spark.streaming.kafka010.{ConsumerStrategies, KafkaUtils, LocationStrategies}
import org.apache.spark.streaming._

import scala.collection.mutable.ArrayBuffer

/**
  * 日志格式：
  * timestamp province city userid adid
  * 某个时间点 某个省份 某个城市 某个用户 某个广告
  */
object AdClickRealTimeStat {


  def main(args: Array[String]): Unit = {

    // 构建Spark上下文
    val sparkConf = new SparkConf().setAppName("streamingRecommendingSystem").setMaster("local[*]")

    // 创建Spark客户端
    val spark = SparkSession.builder().config(sparkConf).getOrCreate()
    val sc = spark.sparkContext
    val ssc = new StreamingContext(sc, Seconds(5))

    // 设置检查点目录
    ssc.checkpoint("./streaming_checkpoint")

    // 获取Kafka配置
    val broker_list = ConfigurationManager.config.getString("kafka.broker.list")
    val topics = ConfigurationManager.config.getString("kafka.topics")

    // kafka消费者配置
    val kafkaParam = Map(
      "bootstrap.servers" -> broker_list,//用于初始化链接到集群的地址
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      //用于标识这个消费者属于哪个消费团体
      "group.id" -> "commerce-consumer-group",
      //如果没有初始化偏移量或者当前的偏移量不存在任何服务器上，可以使用这个配置属性
      //可以使用这个配置，latest自动重置偏移量为最新的偏移量
      "auto.offset.reset" -> "latest",
      //如果是true，则这个消费者的偏移量会在后台自动提交
      "enable.auto.commit" -> (false: java.lang.Boolean)
    )

    // 创建DStream，返回接收到的输入数据
    // LocationStrategies：根据给定的主题和集群地址创建consumer
    // LocationStrategies.PreferConsistent：持续的在所有Executor之间分配分区
    // ConsumerStrategies：选择如何在Driver和Executor上创建和配置Kafka Consumer
    // ConsumerStrategies.Subscribe：订阅一系列主题
    val adRealTimeLogDStream=KafkaUtils.createDirectStream[String,String](ssc, LocationStrategies.PreferConsistent,ConsumerStrategies.Subscribe[String,String](Array(topics),kafkaParam))

    var adRealTimeValueDStream = adRealTimeLogDStream.map(consumerRecordRDD => consumerRecordRDD.value())

    // 用于Kafka Stream的线程非安全问题，重新分区切断血统
    adRealTimeValueDStream = adRealTimeValueDStream.repartition(400)

    // 根据动态黑名单进行数据过滤 (userid, timestamp province city userid adid)
    val filteredAdRealTimeLogDStream = filterByBlacklist(spark,adRealTimeValueDStream)

    // 业务功能一：生成动态黑名单
    generateDynamicBlacklist(filteredAdRealTimeLogDStream)

    // 业务功能二：计算广告点击流量实时统计结果（yyyyMMdd_province_city_adid,clickCount）
    val adRealTimeStatDStream = calculateRealTimeStat(filteredAdRealTimeLogDStream)

    // 业务功能三：实时统计每天每个省份top3热门广告
    calculateProvinceTop3Ad(spark,adRealTimeStatDStream)

    // 业务功能四：实时统计每天每个广告在最近1小时的滑动窗口内的点击趋势（每分钟的点击量）
    calculateAdClickCountByWindow(adRealTimeValueDStream)

    ssc.start()
    ssc.awaitTermination()
  }

  /**
    * 业务功能四：计算最近1小时滑动窗口内的广告点击趋势
    * @param adRealTimeValueDStream
    */
  def calculateAdClickCountByWindow(adRealTimeValueDStream:DStream[String]) {

    // 映射成<yyyyMMddHHMM_adid,1L>格式
    val pairDStream = adRealTimeValueDStream.map{ case consumerRecord  =>
      val logSplited = consumerRecord.split(" ")
      val timeMinute = DateUtils.formatTimeMinute(new Date(logSplited(0).toLong))
      val adid = logSplited(4).toLong

      (timeMinute + "_" + adid, 1L)
    }

    // 计算窗口函数，1小时滑动窗口内的广告点击趋势
    val aggrRDD = pairDStream.reduceByKeyAndWindow((a:Long,b:Long) => (a + b),Minutes(60L), Seconds(10L))

    // 最近1小时内，各分钟的点击量，并保存到数据库
    aggrRDD.foreachRDD{ rdd =>
      rdd.foreachPartition{ items =>
        //保存到数据库
        val adClickTrends = ArrayBuffer[AdClickTrend]()
        for (item <- items){
          val keySplited = item._1.split("_")
          // yyyyMMddHHmm
          val dateMinute = keySplited(0)
          val adid = keySplited(1).toLong
          val clickCount = item._2

          val date = DateUtils.formatDate(DateUtils.parseDateKey(dateMinute.substring(0, 8)))
          val hour = dateMinute.substring(8, 10)
          val minute = dateMinute.substring(10)

          adClickTrends += AdClickTrend(date,hour,minute,adid,clickCount)
        }
        AdClickTrendDAO.updateBatch(adClickTrends.toArray)
      }
    }
  }

  /**
    * 业务功能三：计算每天各省份的top3热门广告
    * @param adRealTimeStatDStream
    */
  def calculateProvinceTop3Ad(spark:SparkSession, adRealTimeStatDStream:DStream[(String, Long)]) {

    // 每一个batch rdd，都代表了最新的全量的每天各省份各城市各广告的点击量
    val rowsDStream = adRealTimeStatDStream.transform{ rdd =>

      // <yyyyMMdd_province_city_adid, clickCount>
      // <yyyyMMdd_province_adid, clickCount>

      // 计算出每天各省份各广告的点击量
      val mappedRDD = rdd.map{ case (keyString, count) =>

        val keySplited = keyString.split("_")
        val date = keySplited(0)
        val province = keySplited(1)
        val adid = keySplited(3).toLong
        val clickCount = count

        val key = date + "_" + province + "_" + adid
        (key, clickCount)
      }

      val dailyAdClickCountByProvinceRDD = mappedRDD.reduceByKey( _ + _ )

      // 将dailyAdClickCountByProvinceRDD转换为DataFrame
      // 注册为一张临时表
      // 使用Spark SQL，通过开窗函数，获取到各省份的top3热门广告
      val rowsRDD = dailyAdClickCountByProvinceRDD.map{ case (keyString, count) =>

        val keySplited = keyString.split("_")
        val datekey = keySplited(0)
        val province = keySplited(1)
        val adid = keySplited(2).toLong
        val clickCount = count

        val date = DateUtils.formatDate(DateUtils.parseDateKey(datekey))

        (date, province, adid, clickCount)

      }

      import spark.implicits._
      val dailyAdClickCountByProvinceDF = rowsRDD.toDF("date","province","ad_id","click_count")

      // 将dailyAdClickCountByProvinceDF，注册成一张临时表
      dailyAdClickCountByProvinceDF.createOrReplaceTempView("tmp_daily_ad_click_count_by_prov")

      // 使用Spark SQL执行SQL语句，配合开窗函数，统计出各身份top3热门的广告
      val provinceTop3AdDF = spark.sql(
        "SELECT "
            + "date,"
            + "province,"
            + "ad_id,"
            + "click_count "
          + "FROM ( "
            + "SELECT "
              + "date,"
              + "province,"
              + "ad_id,"
              + "click_count,"
              + "ROW_NUMBER() OVER(PARTITION BY province ORDER BY click_count DESC) rank "
            + "FROM tmp_daily_ad_click_count_by_prov "
            + ") t "
          + "WHERE rank<=3"
      )

      provinceTop3AdDF.rdd
    }

    // 每次都是刷新出来各个省份最热门的top3广告，将其中的数据批量更新到MySQL中
    rowsDStream.foreachRDD{ rdd =>
      rdd.foreachPartition{ items =>

        // 插入数据库
        val adProvinceTop3s = ArrayBuffer[AdProvinceTop3]()

        for (item <- items){
          val date = item.getString(0)
          val province = item.getString(1)
          val adid = item.getLong(2)
          val clickCount = item.getLong(3)
          adProvinceTop3s += AdProvinceTop3(date,province,adid,clickCount)
        }
        AdProvinceTop3DAO.updateBatch(adProvinceTop3s.toArray)

      }
    }
  }

  /**
    * 业务功能二：计算广告点击流量实时统计
    * @param filteredAdRealTimeLogDStream
    * @return
    */
  def calculateRealTimeStat(filteredAdRealTimeLogDStream:DStream[(Long, String)]):DStream[(String, Long)] = {

    // 计算每天各省各城市各广告的点击量
    // 设计出来几个维度：日期、省份、城市、广告
    // 2015-12-01，当天，可以看到当天所有的实时数据（动态改变），比如江苏省南京市
    // 广告可以进行选择（广告主、广告名称、广告类型来筛选一个出来）
    // 拿着date、province、city、adid，去mysql中查询最新的数据
    // 等等，基于这几个维度，以及这份动态改变的数据，是可以实现比较灵活的广告点击流量查看的功能的

    // date province city userid adid
    // date_province_city_adid，作为key；1作为value
    // 通过spark，直接统计出来全局的点击次数，在spark集群中保留一份；在mysql中，也保留一份
    // 我们要对原始数据进行map，映射成<date_province_city_adid,1>格式
    // 然后呢，对上述格式的数据，执行updateStateByKey算子
    // spark streaming特有的一种算子，在spark集群内存中，维护一份key的全局状态

    val mappedDStream = filteredAdRealTimeLogDStream.map{ case (userid, log) =>
      val logSplited = log.split(" ")

      val timestamp = logSplited(0)
      val date = new Date(timestamp.toLong)
      val datekey = DateUtils.formatDateKey(date)

      val province = logSplited(1)
      val city = logSplited(2)
      val adid = logSplited(4).toLong

      val key = datekey + "_" + province + "_" + city + "_" + adid

      (key, 1L)
    }

    // 在这个dstream中，就相当于，有每个batch rdd累加的各个key（各天各省份各城市各广告的点击次数）
    // 每次计算出最新的值，就在aggregatedDStream中的每个batch rdd中反应出来
    val aggregatedDStream = mappedDStream.updateStateByKey[Long]{ (values:Seq[Long], old:Option[Long]) =>
      // 举例来说
      // 对于每个key，都会调用一次这个方法
      // 比如key是<20151201_Jiangsu_Nanjing_10001,1>，就会来调用一次这个方法7
      // 10个

      // values，(1,1,1,1,1,1,1,1,1,1)

      // 首先根据optional判断，之前这个key，是否有对应的状态
      var clickCount = 0L

      // 如果说，之前是存在这个状态的，那么就以之前的状态作为起点，进行值的累加
      if(old.isDefined) {
        clickCount = old.get
      }

      // values，代表了，batch rdd中，每个key对应的所有的值
      for(value <- values) {
        clickCount += value
      }

      Some(clickCount)
    }

    // 将计算出来的最新结果，同步一份到mysql中，以便于j2ee系统使用
    aggregatedDStream.foreachRDD{ rdd =>

      rdd.foreachPartition{ items =>

        //批量保存到数据库
        val adStats = ArrayBuffer[AdStat]()

        for(item <- items){
          val keySplited = item._1.split("_")
          val date = keySplited(0)
          val province = keySplited(1)
          val city = keySplited(2)
          val adid = keySplited(3).toLong

          val clickCount = item._2
          adStats += AdStat(date,province,city,adid,clickCount)
        }
        AdStatDAO.updateBatch(adStats.toArray)

      }

    }

    aggregatedDStream
  }

  /**
    * 业务功能一：生成动态黑名单
    * @param filteredAdRealTimeLogDStream
    */
  def generateDynamicBlacklist(filteredAdRealTimeLogDStream: DStream[(Long, String)]) {

    // 计算出每5个秒内的数据中，每天每个用户每个广告的点击量
    // 通过对原始实时日志的处理
    // 将日志的格式处理成<yyyyMMdd_userid_adid, 1L>格式
    val dailyUserAdClickDStream = filteredAdRealTimeLogDStream.map{ case (userid,log) =>

      // 从tuple中获取到每一条原始的实时日志
      val logSplited = log.split(" ")

      // 提取出日期（yyyyMMdd）、userid、adid
      val timestamp = logSplited(0)
      val date = new Date(timestamp.toLong)
      val datekey = DateUtils.formatDateKey(date)

      val userid = logSplited(3).toLong
      val adid = logSplited(4)

      // 拼接key
      val key = datekey + "_" + userid + "_" + adid
      (key, 1L)
    }

    // 针对处理后的日志格式，执行reduceByKey算子即可，（每个batch中）每天每个用户对每个广告的点击量
    val dailyUserAdClickCountDStream = dailyUserAdClickDStream.reduceByKey(_ + _)

    // 源源不断的，每个5s的batch中，当天每个用户对每支广告的点击次数
    // <yyyyMMdd_userid_adid, clickCount>
    dailyUserAdClickCountDStream.foreachRDD{ rdd =>
      rdd.foreachPartition{ items =>
        // 对每个分区的数据就去获取一次连接对象
        // 每次都是从连接池中获取，而不是每次都创建
        // 写数据库操作，性能已经提到最高了

        val adUserClickCounts = ArrayBuffer[AdUserClickCount]()
        for(item <- items){
          val keySplited = item._1.split("_")
          val date = DateUtils.formatDate(DateUtils.parseDateKey(keySplited(0)))
          // yyyy-MM-dd
          val userid = keySplited(1).toLong
          val adid = keySplited(2).toLong
          val clickCount = item._2

          //批量插入
          adUserClickCounts += AdUserClickCount(date, userid,adid,clickCount)
        }
        AdUserClickCountDAO.updateBatch(adUserClickCounts.toArray)
      }
    }

    // 现在我们在mysql里面，已经有了累计的每天各用户对各广告的点击量
    // 遍历每个batch中的所有记录，对每条记录都要去查询一下，这一天这个用户对这个广告的累计点击量是多少
    // 从mysql中查询
    // 查询出来的结果，如果是100，如果你发现某个用户某天对某个广告的点击量已经大于等于100了
    // 那么就判定这个用户就是黑名单用户，就写入mysql的表中，持久化
    val blacklistDStream = dailyUserAdClickCountDStream.filter{ case (key, count) =>
      val keySplited = key.split("_")

      // yyyyMMdd -> yyyy-MM-dd
      val date = DateUtils.formatDate(DateUtils.parseDateKey(keySplited(0)))
      val userid = keySplited(1).toLong
      val adid = keySplited(2).toLong

      // 从mysql中查询指定日期指定用户对指定广告的点击量
      val clickCount = AdUserClickCountDAO.findClickCountByMultiKey(date, userid, adid)

      // 判断，如果点击量大于等于100，ok，那么不好意思，你就是黑名单用户
      // 那么就拉入黑名单，返回true
      if(clickCount >= 100) {
        true
      }else{
        // 反之，如果点击量小于100的，那么就暂时不要管它了
        false
      }
    }

    // blacklistDStream
    // 里面的每个batch，其实就是都是过滤出来的已经在某天对某个广告点击量超过100的用户
    // 遍历这个dstream中的每个rdd，然后将黑名单用户增加到mysql中
    // 这里一旦增加以后，在整个这段程序的前面，会加上根据黑名单动态过滤用户的逻辑
    // 我们可以认为，一旦用户被拉入黑名单之后，以后就不会再出现在这里了
    // 所以直接插入mysql即可

    // 我们在插入前要进行去重
    // yyyyMMdd_userid_adid
    // 20151220_10001_10002 100
    // 20151220_10001_10003 100
    // 10001这个userid就重复了

    // 实际上，是要通过对dstream执行操作，对其中的rdd中的userid进行全局的去重, 返回Userid
    val blacklistUseridDStream = blacklistDStream.map(item => item._1.split("_")(1).toLong)

    val distinctBlacklistUseridDStream = blacklistUseridDStream.transform( uidStream => uidStream.distinct() )

    // 到这一步为止，distinctBlacklistUseridDStream
    // 每一个rdd，只包含了userid，而且还进行了全局的去重，保证每一次过滤出来的黑名单用户都没有重复的

    distinctBlacklistUseridDStream.foreachRDD{ rdd =>
      rdd.foreachPartition{ items =>
        val adBlacklists = ArrayBuffer[AdBlacklist]()

        for(item <- items)
          adBlacklists += AdBlacklist(item)

        AdBlacklistDAO.insertBatch(adBlacklists.toArray)
      }
    }
  }

  /**
    * 根据黑名单进行过滤
    * @return
    */
  def filterByBlacklist(spark: SparkSession, adRealTimeValueDStream:DStream[String]):DStream[(Long, String)] = {
    // 刚刚接受到原始的用户点击行为日志之后
    // 根据mysql中的动态黑名单，进行实时的黑名单过滤（黑名单用户的点击行为，直接过滤掉，不要了）
    // 使用transform算子（将dstream中的每个batch RDD进行处理，转换为任意的其他RDD，功能很强大）

    val filteredAdRealTimeLogDStream = adRealTimeValueDStream.transform{ consumerRecordRDD =>

      // 首先，从mysql中查询所有黑名单用户，将其转换为一个rdd
      val adBlacklists = AdBlacklistDAO.findAll()

      val blacklistRDD = spark.sparkContext.makeRDD(adBlacklists.map(item => (item.userid, true)))

      // 将原始数据rdd映射成<userid, tuple2<string, string>>
      val mappedRDD = consumerRecordRDD.map(consumerRecord => {
        val userid = consumerRecord.split(" ")(3).toLong
        (userid,consumerRecord)
      })

      // 将原始日志数据rdd，与黑名单rdd，进行左外连接
      // 如果说原始日志的userid，没有在对应的黑名单中，join不到，左外连接
      // 用inner join，内连接，会导致数据丢失
      val joinedRDD = mappedRDD.leftOuterJoin(blacklistRDD)

      val filteredRDD = joinedRDD.filter{ case (userid,(log, black)) =>
        // 如果这个值存在，那么说明原始日志中的userid，join到了某个黑名单用户
        if(black.isDefined && black.get) false else true
      }

      filteredRDD.map{ case (userid,(log, black)) => (userid, log)}
    }

    filteredAdRealTimeLogDStream
  }

}
