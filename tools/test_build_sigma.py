"""Regression checks for deploy URLs, so cached JS cannot outlive changed HTML."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

class SigmaBuildTest(unittest.TestCase):
    def test_asset_urls_follow_content_and_stay_relative(self):
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp)/'source'
            paths = ['tools/build_sigma.py','src/main/resources/schema-mariadb.sql',
                     'sigma/public/api.php','docs/sigma.md']
            paths += ['src/main/resources/web/'+name for name in
                      ['index.html','vote.html','app.js','vote.js','style.css']]
            for rel in paths:
                dest = fixture/rel
                dest.parent.mkdir(parents=True,exist_ok=True)
                shutil.copy2(root/rel,dest)
            (fixture/'sigma/private').mkdir(parents=True)
            dest = Path(tmp)/'deploy'
            def build():
                subprocess.run([sys.executable,str(fixture/'tools/build_sigma.py'),str(dest)],
                               check=True,capture_output=True)
                return [(dest/'web/futebol'/name).read_text(encoding='utf-8')
                        for name in ['index.html','vote.html']]
            pages = build()
            for name,page in zip(['app.js','vote.js'],pages):
                content = (fixture/'src/main/resources/web'/name).read_bytes()
                version = hashlib.sha256(content).hexdigest()[:16]
                self.assertIn('src="'+name+'?v='+version+'"',page)
                self.assertIn('href="style.css?v=',page)
                self.assertNotIn('src="/',page)
                self.assertNotIn('href="/style.css',page)
            self.assertEqual(pages,build())
            js = fixture/'src/main/resources/web/app.js'
            js.write_bytes(js.read_bytes()+b'\n// Changed deployment\n')
            new_pages = build()
            self.assertNotEqual(pages[0],new_pages[0])
            self.assertEqual(pages[1],new_pages[1])
            self.assertEqual(js.read_text(encoding='utf-8'),(dest/'web/futebol/app.js').read_text(encoding='utf-8'))

if __name__ == '__main__':
    unittest.main()
