# Pinned Linux wireless-regdb

Source: https://git.kernel.org/pub/scm/linux/kernel/git/sforshee/wireless-regdb.git

- `PINNED_SHA` — git commit of `db.txt`
- `db.txt` — snapshot used by `../gen_softap_regdb_channels.py`

To refresh:

```bash
git clone https://git.kernel.org/pub/scm/linux/kernel/git/sforshee/wireless-regdb.git
cd wireless-regdb
git rev-parse HEAD > ../PINNED_SHA   # adjust path into this directory
cp db.txt .
cd ../../..
python3 tools/gen_softap_regdb_channels.py
```

Commit `db.txt`, `PINNED_SHA`, and regenerated `SoftApRegdbChannels.java` together.
