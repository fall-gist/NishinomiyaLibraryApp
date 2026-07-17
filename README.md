# NishinomiyaLibraryApp

西宮市立図書館 家族用Androidアプリ(非公式・非公開)

家族全員分の「借りている本・返却期限・予約状況・マイ本棚」を1つのアプリに集約し、
返し忘れ・受取忘れをなくすためのアプリ。

## ドキュメント

| ドキュメント | 内容 |
|---|---|
| [docs/spec.md](docs/spec.md) | 確定済みのアプリ仕様 |
| [docs/backend-design.md](docs/backend-design.md) | バックエンド(データ層)設計書 |
| [docs/handoff.md](docs/handoff.md) | **実装担当者はまずこれを読む**(実装順序・受入条件・禁止事項) |
| [docs/site-research.md](docs/site-research.md) | 図書館サイトの調査結果(エンドポイント・HTML構造) |

## 現在のステータス

- [x] 仕様確定
- [x] 図書館サイト調査
- [x] バックエンド設計
- [ ] バックエンド実装(M0〜M5: [docs/handoff.md](docs/handoff.md) 参照)
- [ ] フロントエンド設計(バックエンド完成後に別途)
- [ ] フロントエンド実装

## 注意

- 本アプリは非公式です。西宮市および西宮市立図書館とは関係ありません
- 図書館サイトへは読み取りアクセスのみ。認証情報はリポジトリに含めないこと
