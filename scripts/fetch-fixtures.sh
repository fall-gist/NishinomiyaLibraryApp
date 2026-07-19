#!/usr/bin/env bash
# テスト用フィクスチャHTMLを西宮市立図書館サイトから取得する。
#
# 使い方:
#   LIB_CARD=カード番号 LIB_PASS=パスワード ./scripts/fetch-fixtures.sh [出力先dir]
#
# - 認証情報は必ず環境変数で渡す(引数・ファイル直書き禁止)
# - 出力先省略時: app/src/test/resources/fixtures
# - サイトに負荷をかけないよう、リクエスト間に1秒スリープする
# - 取得後、コミット前に個人情報が含まれないか目視確認すること

set -euo pipefail

BASE="https://tosho.nishi.or.jp/licsxp-opac"
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
OUT="${1:-app/src/test/resources/fixtures}"
JAR="$(mktemp)"
trap 'rm -f "$JAR"' EXIT

: "${LIB_CARD:?環境変数 LIB_CARD にカード番号を設定してください}"
: "${LIB_PASS:?環境変数 LIB_PASS にパスワードを設定してください}"

mkdir -p "$OUT"

req() { # method url output [curl args...]
  local method="$1" url="$2" out="$3"; shift 3
  curl -sS -A "$UA" -b "$JAR" -c "$JAR" -X "$method" "$@" "$url" -o "$out" \
    -w "$(basename "$out") %{http_code} %{size_download}B\n"
  sleep 1
}

echo "== 検索フォーム(セッション確立)"
req GET "$BASE/WOpacEsSchCmpdDispAction.do" "$OUT/search_form.html"

echo "== 検索実行(キーワード: 図鑑)"
req POST "$BASE/WOpacEsSchCmpdExecAction.do" "$OUT/search_result.html" \
  --data-urlencode "returnid=" --data-urlencode "hash=" \
  --data-urlencode "gamenid=tiles.WEsSchCmpd" \
  --data-urlencode "tifKanrabtn=1" --data-urlencode "chkflg=" \
  --data-urlencode "loccodschkflg=nocheck" \
  --data-urlencode "condition1Text=図鑑"

echo "== 書誌詳細(検索結果の先頭tilcodを使用)"
TILCOD=$(grep -oE 'tilcod=[0-9]{13}' "$OUT/search_result.html" | head -1 | cut -d= -f2)
req GET "$BASE/WOpacTifTilListToTifTilDetailAction.do?urlNotFlag=1&tilcod=$TILCOD" "$OUT/book_detail.html"

echo "== 休館日カレンダー(中央図書館)"
req GET "$BASE/WOpacMnuTopInitAction.do?WebLinkFlag=1&moveToGamenId=msgcld&loccod=001" "$OUT/calendar.html"

echo "== ログイン"
req GET "$BASE/OpacInitLoginAction.do?subSystemFlag=0" "$OUT/login_form.html"
req POST "$BASE/j_security_check?subSystemFlag=0" "$OUT/after_login.html" -L \
  --data-urlencode "hash=" --data-urlencode "gamenid=tiles.WMnuTop" \
  --data-urlencode "username=$LIB_CARD" \
  --data-urlencode "j_username=0000000000000000$LIB_CARD" \
  --data-urlencode "h_username=" \
  --data-urlencode "j_password=$LIB_PASS"
if ! grep -q "ログアウト" "$OUT/after_login.html"; then
  echo "ERROR: ログインに失敗した可能性があります(after_login.htmlを確認)" >&2
  exit 1
fi

echo "== メニュー(hash取得)"
req GET "$BASE/WOpacMnuTopInitAction.do?WebLinkFlag=1" "$OUT/menu.html"
HASH=$(grep -oE 'name="hash" value="[a-f0-9]+"' "$OUT/menu.html" | head -1 | grep -oE '[a-f0-9]{20,}')
GAMEN=$(grep -oE 'name="gamenid" value="[^"]+"' "$OUT/menu.html" | head -1 | sed 's/.*value="//;s/"//')

echo "== 利用者ページ3画面"
for g in usrlend usrrsv mybooklist; do
  req POST "$BASE/WOpacMnuTopToPwdLibraryAction.do?gamen=$g" "$OUT/$g.html" \
    --data-urlencode "hash=$HASH" --data-urlencode "gamenid=$GAMEN"
done

echo "== 読書履歴（初回表示）"
req POST "$BASE/WOpacMnuTopToPwdLibraryAction.do?gamen=usrread&initFlag=0" "$OUT/usrread.html" \
  --data-urlencode "hash=$HASH" --data-urlencode "gamenid=$GAMEN"

echo "== 個人情報のマスキング(カード番号を同桁数の9に置換)"
MASK=$(printf '9%.0s' $(seq ${#LIB_CARD}))
sed -i.bak "s/$LIB_CARD/$MASK/g" "$OUT"/*.html && rm -f "$OUT"/*.html.bak
if grep -rl "$LIB_CARD" "$OUT" >/dev/null 2>&1; then
  echo "ERROR: マスキング後もカード番号が残っています" >&2
  exit 1
fi

echo
echo "完了: $OUT に保存しました。"
echo "カード番号は自動マスク済みですが、コミット前に氏名など他の個人情報が"
echo "含まれていないか目視確認してください。"
