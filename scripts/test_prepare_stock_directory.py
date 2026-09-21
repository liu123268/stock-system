import importlib.util
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'target/market-python'))
sys.path.insert(0, str(ROOT / 'scripts'))
from stock_directory_pinyin import pinyin_initials

PREPARE_SPEC = importlib.util.spec_from_file_location(
    'prepare_stock_directory', ROOT / 'scripts/prepare-stock-directory.py')
PREPARE_MODULE = importlib.util.module_from_spec(PREPARE_SPEC)
PREPARE_SPEC.loader.exec_module(PREPARE_MODULE)


class PinyinInitialsTest(unittest.TestCase):
    def test_phrase_dictionary_and_nfkc_normalization(self):
        self.assertEqual('tbdg', pinyin_initials('特变电工'))
        self.assertEqual('cqyh', pinyin_initials('重庆银行'))
        self.assertEqual('stgl', pinyin_initials('ＳＴ桂林'))

    def test_rejects_name_without_searchable_letters(self):
        with self.assertRaisesRegex(ValueError, 'no searchable pinyin initials'):
            pinyin_initials('＊＊')

    def test_enriches_existing_trusted_directory_without_market_download(self):
        directory = {'stocks': [{'code': '600089.SH', 'name': '特变电工'}]}

        self.assertEqual(
            'tbdg',
            PREPARE_MODULE.enrich_pinyin(directory)['stocks'][0]['pinyinInitials'],
        )


if __name__ == '__main__':
    unittest.main()
