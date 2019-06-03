/*
 Navicat Premium Data Transfer

 Source Server         : localhost
 Source Server Type    : MySQL
 Source Server Version : 50720
 Source Host           : localhost
 Source Database       : commerce

 Target Server Type    : MySQL
 Target Server Version : 50720
 File Encoding         : utf-8

 Date: 11/03/2017 11:23:32 AM
*/

SET FOREIGN_KEY_CHECKS = 0;


-- ----------------------------
--  Table structure for `session_aggr_stat`
-- ----------------------------
DROP TABLE IF EXISTS `session_aggr_stat`;
CREATE TABLE `session_aggr_stat` (
  `taskid` varchar(255) DEFAULT NULL,
  `session_count` int(11) DEFAULT NULL,
  `visit_length_1s_3s_ratio` double DEFAULT NULL,
  `visit_length_4s_6s_ratio` double DEFAULT NULL,
  `visit_length_7s_9s_ratio` double DEFAULT NULL,
  `visit_length_10s_30s_ratio` double DEFAULT NULL,
  `visit_length_30s_60s_ratio` double DEFAULT NULL,
  `visit_length_1m_3m_ratio` double DEFAULT NULL,
  `visit_length_3m_10m_ratio` double DEFAULT NULL,
  `visit_length_10m_30m_ratio` double DEFAULT NULL,
  `visit_length_30m_ratio` double DEFAULT NULL,
  `step_length_1_3_ratio` double DEFAULT NULL,
  `step_length_4_6_ratio` double DEFAULT NULL,
  `step_length_7_9_ratio` double DEFAULT NULL,
  `step_length_10_30_ratio` double DEFAULT NULL,
  `step_length_30_60_ratio` double DEFAULT NULL,
  `step_length_60_ratio` double DEFAULT NULL,
  KEY `idx_task_id` (`taskid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
--  Table structure for `session_detail`
-- ----------------------------
DROP TABLE IF EXISTS `session_detail`;
CREATE TABLE `session_detail` (
  `taskid` varchar(255) DEFAULT NULL,
  `userid` int(11) DEFAULT NULL,
  `sessionid` varchar(255) DEFAULT NULL,
  `pageid` int(11) DEFAULT NULL,
  `actionTime` varchar(255) DEFAULT NULL,
  `searchKeyword` varchar(255) DEFAULT NULL,
  `clickCategoryId` int(11) DEFAULT NULL,
  `clickProductId` int(11) DEFAULT NULL,
  `orderCategoryIds` varchar(255) DEFAULT NULL,
  `orderProductIds` varchar(255) DEFAULT NULL,
  `payCategoryIds` varchar(255) DEFAULT NULL,
  `payProductIds` varchar(255) DEFAULT NULL,
  KEY `idx_task_id` (`taskid`),
  KEY `idx_session_id` (`sessionid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
--  Table structure for `session_random_extract`
-- ----------------------------
DROP TABLE IF EXISTS `session_random_extract`;
CREATE TABLE `session_random_extract` (
  `taskid` varchar(255) DEFAULT NULL,
  `sessionid` varchar(255) DEFAULT NULL,
  `startTime` varchar(50) DEFAULT NULL,
  `searchKeywords` varchar(255) DEFAULT NULL,
  `clickCategoryIds` varchar(255) DEFAULT NULL,
  KEY `idx_task_id` (`taskid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
--  Table structure for `top10_category`
-- ----------------------------
DROP TABLE IF EXISTS `top10_category`;
CREATE TABLE `top10_category` (
  `taskid` varchar(255) DEFAULT NULL,
  `categoryid` int(11) DEFAULT NULL,
  `clickCount` int(11) DEFAULT NULL,
  `orderCount` int(11) DEFAULT NULL,
  `payCount` int(11) DEFAULT NULL,
  KEY `idx_task_id` (`taskid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ----------------------------
--  Table structure for `top10_session`
-- ----------------------------
DROP TABLE IF EXISTS `top10_session`;
CREATE TABLE `top10_session` (
  `taskid` varchar(255) DEFAULT NULL,
  `categoryid` int(11) DEFAULT NULL,
  `sessionid` varchar(255) DEFAULT NULL,
  `clickCount` int(11) DEFAULT NULL,
  KEY `idx_task_id` (`taskid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

SET FOREIGN_KEY_CHECKS = 1;
