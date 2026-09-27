"""Extract the archived game and apply narrowly scoped PHP 5.6 DB fixes."""
import pathlib
import sys
import zipfile

archive = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(archive) as z:
    for info in z.infolist():
        target = destination / info.filename
        if not target.resolve().is_relative_to(destination.resolve()):
            raise ValueError("unsafe zip path")
    z.extractall(destination)

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
