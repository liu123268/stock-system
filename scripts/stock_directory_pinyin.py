import re
import unicodedata

from pypinyin import Style, lazy_pinyin


def pinyin_initials(name):
    """Return normalized lowercase initials; pypinyin applies its phrase dictionary first."""
    normalized = unicodedata.normalize('NFKC', name).strip()
    initials = ''.join(lazy_pinyin(normalized, style=Style.FIRST_LETTER)).lower()
    result = re.sub(r'[^a-z]', '', initials)
    if not result:
        raise ValueError('Name has no searchable pinyin initials')
    return result
