import copy
import hashlib
import io
import unittest
import zipfile

import release_index as index


class ReleaseIndexTest(unittest.TestCase):
    def payload(self, *, version='0.1.0-alpha.4', properties=None, permissions='[workspace.read]'):
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w') as jar:
            jar.writestr('module-metadata.yaml', f'''schemaVersion: 1
modules:
  - moduleId: zalava-module-time
    version: {version}
    artifact:
      groupId: org.zalava.modules
      artifactId: zalava-module-time
      version: {version}
    source:
      repository: https://github.com/Zalava/zalava-module-time.git
      license: Apache-2.0
    compatibility:
      zalavaRuntime: ">=0.1.0-alpha.1 <1.0.0"
    security:
      permissions: {permissions}
''')
            jar.writestr('module.properties', properties or f'module.version={version}')
            jar.writestr('META-INF/services/org.zalava.api.ZalavaModule', 'org.example.Module')
        return out.getvalue()

    def release(self, **kwargs):
        return index.release(self.payload(**kwargs),
                             'https://github.com/Zalava/zalava-module-time',
                             'v0.1.0-alpha.4', 'a' * 40)

    def test_binds_released_bytes_permissions_and_source(self):
        module_id, release = self.release()
        self.assertEqual(module_id, 'zalava-module-time')
        self.assertEqual(release['security']['permissions'], ['workspace.read'])
        self.assertEqual(release['source']['revision'], 'a' * 40)
        self.assertEqual(release['artifact']['sha256'], hashlib.sha256(self.payload()).hexdigest())

    def test_refuses_rewriting_existing_release(self):
        module_id, release = self.release()
        original = index.append(None, module_id, release)
        self.assertEqual(index.append(copy.deepcopy(original), module_id, release), original)
        changed = copy.deepcopy(release)
        changed['artifact']['sha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'cannot be rewritten'):
            index.append(original, module_id, changed)

    def test_rejects_wrong_tag_and_descriptor(self):
        for kwargs in ({'version': '0.1.0-alpha.5'}, {'properties': 'module.version=0.0.0'}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                self.release(**kwargs)

    def test_rejects_missing_permissions_and_unpinned_source(self):
        with self.assertRaises(ValueError):
            self.release(permissions='null')
        with self.assertRaises(ValueError):
            index.release(self.payload(), 'https://github.com/Zalava/zalava-module-time',
                          'v0.1.0-alpha.4', 'main')

    def test_rejects_foreign_source_and_index(self):
        with self.assertRaises(ValueError):
            index.release(self.payload(), 'https://github.com/other/zalava-module-time',
                          'v0.1.0-alpha.4', 'a' * 40)
        module_id, release = self.release()
        with self.assertRaises(ValueError):
            index.append({'schemaVersion': 1, 'moduleId': 'other', 'releases': []}, module_id, release)


if __name__ == '__main__':
    unittest.main()
