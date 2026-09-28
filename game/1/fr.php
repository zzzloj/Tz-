<?php
header("Content-type:text/vnd.wap.wml;charset=utf-8");
echo "<?xml version=\"1.0\"?>\n";
echo "<!DOCTYPE wml PUBLIC \"-//WAPFORUM//DTD WML 1.1//EN\" \"http://www.wapforum.org/DTD/wml_1.1.xml\">";
echo "<wml>\n";
 print "<card id=\"x\" title=\"ФОТО ЮЗЕРА\">
<p>
<img src=\"../photos/$img\" alt=\"Загрузка...\"/>
";
print "</p>
</card>
</wml>";
?>