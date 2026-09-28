<?php

require("config.ssp");
$link = @mysql_pconnect ($server, $user, $dbpass)
                or die ("Can not connect to MySQL");
     
     @mysql_select_db($dbname) or die ("error select the database...");

		  $result = mysql_query ("select * from users order by id desc;");
        while($inf = @mysql_fetch_array($result)) {
@mysql_query ("Update users set regtime2='".date("d-m-Y", $inf["regtime"])."'");}
?>