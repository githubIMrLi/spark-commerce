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
--  Table structure for `area_top3_product`
-- ----------------------------
DROP TABLE IF EXISTS `area_top3_product`;
CREATE TABLE `area_top3_product` (
  `taskid` varchar(255) DEFAULT NULL,
  `area` varchar(255) DEFAULT NULL,
  `areaLevel` varchar(255) DEFAULT NULL,
  `productid` int(11) DEFAULT NULL,
  `cityInfos` varchar(255) DEFAULT NULL,
  `clickCount` int(11) DEFAULT NULL,
  `productName` varchar(255) DEFAULT NULL,
  `productStatus` varchar(255) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
