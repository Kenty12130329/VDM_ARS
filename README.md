# VDM-PP Random Search Tool (Java_RandomSearch)

本ツールは、SMTソルバー（Z3など）を使用せず、VDMJ（VDM-PPインタプリタ）を直接制御してランダム探索を行うJavaプログラムです。
モデルの不変条件（inv）違反を検出し、その再現トレースを出力する対照実験用ツールとして設計されています。

---

## 特徴

1. トランジション（操作名）の自動抽出
   * .vdmpp モデルファイルを読み込み、operations ブロックから呼び出し可能なメソッド名を自動抽出します。事前な transition.csv の作成・指定は不要です。
2. モデルクラス名・変数の自動判定
   * モデルファイル内のクラス名（class <ClassName>）や状態変数を自動判定して動的にインスタンス化・パースを行います。
3. 不変条件違反検出時の即時停止と再現トレース出力
   * 不変条件違反を検出した瞬間に探索を終了し、初期状態からバグ到達までの手順を found_trace.txt に出力します。

---

## フォルダ構成

* src/main/java/com/fujitsu/robot/
  * ArsMain.java: エントリーポイント。引数の解析と実行制御を担当。
  * ArsExplorer.java: ランダム探索のメインループ。引数の自動生成、不変条件の論理評価、収束判定の統括を担当。
  * ArsVDMController.java: VDMJプロセスとの対話型通信制御、状態ダンプのパースを担当。
  * CheckObject.java: チェック対象（Mw, w, Mr, r）のデータ構造。
  * ConvergenceChecker.java: 到達したチェック対象の累積到達数と連続未増分による収束判定を担当。
  * StringValue.java: パース用ユーティリティクラス。
* docs/
  * system_architecture_ja.puml: システム構成図（日本語
  * system_architecture_en.puml: システム構成図（英語版
* jar/: 依存ライブラリ (vdmj-4.6.0.jar)
* resources/: 検証対象の VDM-PP モデルファイル
  * book.vdmpp: 図書館モデル（正常系）
  * manual_mutants/book_M1.vdmpp ~ book_M15.vdmpp: 図書館モデルのミュータント（バグ変異体 M1~M15）
  * ConveniPayment44.vdmpp: コンビニ収納代行 外部引数モデル（正常系）
  * ConveniPayment44_bug_A.vdmpp: コンビニ収納代行 外部引数モデル（バグA: 二重支払）
  * ConveniPayment44_bug_B.vdmpp: コンビニ収納代行 外部引数モデル（バグB: 到達不能）
* build.bat: コンパイル用バッチスクリプト
* run_ars.bat: ビルドおよび探索実行用バッチスクリプト

---

## ビルドと実行方法

### 1. ビルド手順
```bash
build.bat
```
※ bin/ ディレクトリにクラスファイルが出力されます。

### 2. 実行手順
```bash
run_ars.bat <モデルファイルのパス> [探索の最大深さ] [最大試行回数]
```
* 第1引数: 検証対象の .vdmpp ファイルのパス（必須）
* 第2引数: 各試行（Run）における最大ステップ数（デフォルト: 10）
* 第3引数: 最大試行回数（デフォルト: 1000）

---

## 主な検証シナリオと実行例

### 1. 図書館モデルの検証 (book.vdmpp / manual_mutants)
正常系モデルの動作確認：
```bash
run_ars.bat resources/book.vdmpp 10 1000
```

ミュータントモデル（M1〜M15）でのバグ検出テスト：
```bash
run_ars.bat resources/manual_mutants/book_M1.vdmpp 10 1000
```
* 結果: バグが検出された場合、直ちに探索を中断し found_trace.txt に再現手順が出力されます。

### 2. コンビニ支払システムの検証 (ConveniPayment44)
正常系モデルの完走確認：
```bash
run_ars.bat resources/ConveniPayment44.vdmpp 10 100
```

外部引数チェックデジット制約による探索限界の実証：
```bash
run_ars.bat resources/ConveniPayment44_bug_A.vdmpp 10 100
```
* 結果: モジュラス10計算およびDB適合をランダムで引き当てる確率は天文学的に低いため、100%すべての試行において1ステップ目の Scan(comp_code, cust_id, amt, check_digit) で事前条件エラーとなり、[Dead End] となって探索がストップする。

---

## 出力されるログファイル

探索終了後、直下に以下のファイルが出力されます。

1. statistics_summary.txt
   * サマリーレポート: 実行時間（ms）、総ステップ数、最大深さ、違反検出の有無（true/false）および詳細理由。
2. found_trace.txt
   * 違反再現トレース: 不変条件違反が検出された際、初期状態（Step 0）からバグ到達までの操作手順と最終変数値。
3. transition_log.csv
   * 実行ログ: 各 Run / Step で実行された操作名および引数の履歴。
4. variable_history.csv
   * 状態変数推移: 全ステップにおけるモデル状態変数の推移データ。

---

## 終了条件

探索は以下のいずれかで終了します：
1. 不変条件（inv）違反の検出：検出した瞬間に即座に全探索を中断・終了します。
2. 設定した最大試行回数（maxRuns）への到達：全試行を完走して終了します。
