<?
header("Cache-Control: no-cache, no-store, must-revalidate, max-age=0");
header("Pragma: no-cache");
?>
<? require_once("classes/php/DesignerWorld.php"); 
$loc = new Location();
$loc->getLocationList(); ?>
<? if($com=="list"):
$arLoc = $loc->getSize($column,$row,$column_count,$row_count);?>
<script language="javascript">
parent.document.DesignerWorld.setLocationList("<?=$arLoc["WIDTH"]?>","<?=$arLoc["HEIGHT"]?>","<?=$arLoc["LOCATION"]?>","<?=$arLoc["NO_CARD_LOCATION"]?>","<?=$arLoc["SUF_LOCATION"]?>");
</script>
Загрузка карты завершена
<? endif;?>
<? if($com=="getlocation"):
$arLoc = $loc->getLocation($location);?>
<script language="javascript">
parent.document.DesignerWorld.setLocation('<?=$arLoc["NAME"]?>','<?=$arLoc["DESCRIPTION"]?>','<?=$arLoc["WORK"]?>','<?=$arLoc["BACKUP"]?>');
</script>
Загрузка локации <?=$location?> завершена
<? endif;?>
<? if($com=="save_desc_loc_get"):?>
<script language="javascript">
parent.saveDescLoc();
</script>
Приступаю к сохранению описания
<? endif;?>
<? if($com=="save_desc_loc_set"):
$loc->savedescLoc($nameLoc,$descLoc);
$arLoc = $loc->getLocation($nameLoc);?>
<script language="javascript">
parent.document.DesignerWorld.setLocation('<?=$arLoc["NAME"]?>','<?=$arLoc["DESCRIPTION"]?>','<?=$arLoc["WORK"]?>','<?=$arLoc["BACKUP"]?>');
</script>
Сохранение описания локации <?=$nameLoc?> завершено
<? endif; ?>
<? if($com=="delete_desc_loc_get"):
$loc->deletedescLoc($location);
$arLoc = $loc->getLocation($location);?>
<script language="javascript">
parent.document.DesignerWorld.setLocation('<?=$arLoc["NAME"]?>','<?=$arLoc["DESCRIPTION"]?>','<?=$arLoc["WORK"]?>','<?=$arLoc["BACKUP"]?>');
</script>
Описание локации <?=$location?> удалено
<? endif?>
<!-- сохранение программного описания локации -->
<? if($com=="save_work_loc_get"):?>
<script language="javascript">
parent.saveWorkLoc();
</script>
Приступаю к сохранению программного описания локации
<? endif;?>
<? if($com=="save_work_loc_set"):
$loc->saveWorkLoc($nameLoc,$workLoc);
$arLoc = $loc->getLocation($nameLoc);?>
<script language="javascript">
parent.document.DesignerWorld.setLocation('<?=$arLoc["NAME"]?>','<?=$arLoc["DESCRIPTION"]?>','<?=$arLoc["WORK"]?>','<?=$arLoc["BACKUP"]?>');
parent.document.DesignerWorld.repaintMap();
</script>
Сохранение программного описания локации <?=$nameLoc?> завершено
<? endif; ?>
<!-- сохранение и удаление резервного файла локаций -->
<? if($com=="save_backup_loc_get"):?>
<script language="javascript">
parent.saveBackupLoc();
</script>
Приступаю к сохранению резервной копии локации
<? endif;?>
<? if($com=="save_backup_loc_set"):
$loc->saveBackupLoc($nameLoc,$backupLoc);
$arLoc = $loc->getLocation($nameLoc);?>
<script language="javascript">
parent.document.DesignerWorld.setLocation('<?=$arLoc["NAME"]?>','<?=$arLoc["DESCRIPTION"]?>','<?=$arLoc["WORK"]?>','<?=$arLoc["BACKUP"]?>');
</script>
Сохранение резервной копии локации <?=$nameLoc?> завершено
<? endif; ?>
<? if($com=="delete_backup_loc_get"):
$loc->deleteBackupLoc($location);
$arLoc = $loc->getLocation($location);?>
<script language="javascript">
parent.document.DesignerWorld.setLocation('<?=$arLoc["NAME"]?>','<?=$arLoc["DESCRIPTION"]?>','<?=$arLoc["WORK"]?>','<?=$arLoc["BACKUP"]?>');
</script>
Резервная копия локации <?=$location?> удалена
<? endif?>
<!-- алл сохранение -->
<? if($com=="save_all_get"): ?>
<script language="javascript">
parent.saveAllLoc();
</script>
Приступаю к сохранению локации
<? endif; ?>
<? if($com=="save_all_set"): 
$loc->savedescLoc($nameLoc,$descLoc);
$loc->saveWorkLoc($nameLoc,$workLoc);
$loc->saveBackupLoc($nameLoc,$backupLoc);
$arLoc = $loc->getLocation($nameLoc);?>
<script language="javascript">
parent.document.DesignerWorld.setLocation('<?=$arLoc["NAME"]?>','<?=$arLoc["DESCRIPTION"]?>','<?=$arLoc["WORK"]?>','<?=$arLoc["BACKUP"]?>');
parent.document.DesignerWorld.repaintMap();
</script>
Локация <?=$nameLoc?> сохранена
<? endif; ?>
<? if($com=="location_delete_all"):
$loc->deletedescLoc($location);
$loc->deleteWorkLoc($location);
$loc->deleteBackupLoc($location);
$arLoc = $loc->getLocation($location);?>
<script language="javascript">
parent.document.DesignerWorld.repaintMap();
</script>
Локация <?=$location?> удалена
<? endif?>