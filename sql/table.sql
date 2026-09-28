DROP TABLE IF EXISTS `users`;
CREATE TABLE `users` (
  `names` VARCHAR(10),
  `vals` text NOT NULL,
  `pass` VARCHAR(10),
  `lastrefr` VARCHAR(11),
  `nick` VARCHAR(10),
  `gametime` VARCHAR(11),
  `status` VARCHAR(2),
  `sent` text NOT NULL,
  `regtime` VARCHAR(11),
  `refrint` text NOT NULL,
  `messlim` text NOT NULL,
  `mode` text NOT NULL,
  `email` VARCHAR(40),
  `pi` VARCHAR(3)
) TYPE=MyISAM;