"""Copy the game tree and apply narrowly scoped PHP 5.6 fixes."""
import pathlib
import shutil
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
shutil.copytree(source / "game", destination / "game")

for suffix in ('', '/1', '/2'):
    path = destination / ('game' + suffix) / 'datafunc.php'
    contents = path.read_bytes()
    marker = b'$sesDB = @mysql_connect($server,$user,$dbpass);'
    assert contents.count(marker) == 1, str(path)
    # The original 2007 INSERT statements omit several NOT NULL columns.
    contents = contents.replace(
        marker,
        marker + b'\n  if ($sesDB) @mysql_query("SET SESSION sql_mode=\'\'");',
    )
    path.write_bytes(contents)

for server in ('1', '2'):
    path = destination / 'game' / server / 'f_connect.php'
    contents = path.read_bytes()
    old = b'@implode("",(array)(@file(@implode("",(array)(@file("serverurl.dat"))).$srv."/g.php?site=connect2&sid=$login&login=$login&p=$p&f_c=$f_c&clan=$clan&tacc=$tacc&tnews=$tnews&ip=".$_SERVER["REMOTE_ADDR"]."&data=".urlencode($data))))'
    new = b'legacy_internal_request($srv."/g.php?site=connect2&sid=$login&login=$login&p=$p&f_c=$f_c&clan=$clan&tacc=$tacc&tnews=$tnews&ip=".$_SERVER["REMOTE_ADDR"]."&data=".urlencode($data))'
    assert contents.count(old) == 1, str(path)
    path.write_bytes(contents.replace(old, new))

# Character data stores an integer age. The archived form instead suggests a
# birth date, which reg2.php parses with intval() and then rejects as too old.
for server in ('1', '2'):
    path = destination / 'game' / server / 'f_site_reg.dat'
    contents = path.read_bytes()
    old = 'Дата рождения (31/12/2009):<br/><input name=\\"age\\" value=\\"31/12/2009\\" maxlength=\\"10\\"/>'.encode('cp1251')
    new = 'Возраст (полных лет):<br/><input name=\\"age\\" value=\\"18\\" maxlength=\\"2\\"/>'.encode('cp1251')
    assert contents.count(old) == 1, str(path)
    path.write_bytes(contents.replace(old, new))

# Links hardcoded to the game's original (long dead) domains point back to
# this site instead, e.g. the news and FAQ pages.
OLD_ROOTS = (b'http://bigwap.org/game/', b'http://zlo.holys.com.ru/game/', b'http://worldwap.info/game/')
for path in (destination / 'game').rglob('*'):
    if not path.is_file() or (path.suffix not in ('.php', '.dat', '.htm') and 'items' not in path.parts):
        continue
    contents = path.read_bytes()
    if not any(root in contents for root in OLD_ROOTS):
        continue
    for root in OLD_ROOTS:
        contents = contents.replace(root, b'/')
    path.write_bytes(contents)
