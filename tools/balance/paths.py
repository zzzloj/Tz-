"""Where the balance scripts read and write (run them from anywhere)."""
import os
HERE = os.path.dirname(os.path.abspath(__file__))
CONTENT = os.path.normpath(os.path.join(HERE, '..', '..', 'content'))
OUT = os.path.join(HERE, 'out')          # intermediate files, not committed
os.makedirs(OUT, exist_ok=True)
def out(name): return os.path.join(OUT, name)
def here(name): return os.path.join(HERE, name)
