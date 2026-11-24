# TownyEconomyLaw

Minecraft **Purpur 1.21.11 / Java 21** 向けの経済・司法プラグインです。

## 導入

1. サーバーに 1.21.11 対応版の **Towny**、**Vault**、および Vault に登録する経済プラグインを入れます。経済プロバイダーは小数第2位までの入出金に対応する必要があります。
2. ショップ価格を調べる場合は **QuickShop-Hikari** または **QuickShop（Reremake 系）** を入れます。
3. `towny-economy-law-1.0.0.jar` を `plugins/` に入れ、サーバーを起動します。`/tel help` でコマンドを表示できます。

JDK 21 と Maven で `mvn clean verify` を実行すると、`target/towny-economy-law-1.0.0.jar` が生成されます。通常の Maven ビルドは Maven Central と `repo.purpurmc.org` に接続します。

## ショップと市場

| コマンド | 操作 |
| --- | --- |
| `/shopavg` または `/shopaverage` | 現在のワールドの価格 GUI を開きます。カテゴリをクリックし、アイテムにカーソルを合わせると通貨別の平均単価と店舗数を確認できます。右下の時計で価格を再取得できます。 |
| `/shopavg hand` | メインハンドのアイテムの平均単価をチャットに表示します。 |
| `/tel market` | 株式と暗号資産の市場 GUI。左クリックで1口購入、右クリックで1口売却します。 |
| `/tel buy <銘柄> <数量>` / `/tel sell <銘柄> <数量>` | 指定数量を売買します。 |
| `/tel company create <ID> <名前>` | 民間組織を設立します。 |
| `/tel company invite <ID> <プレイヤー>` / `/tel company join <ID>` | 代表が招待し、招待されたプレイヤーが参加します。 |
| `/tel company transfer <ID> <所属プレイヤー>` / `/tel company leave <ID>` | 代表権を移譲します。メンバーは脱退できます。 |
| `/tel company info <ID>` / `/tel company list` | 組織情報や登録済み ID を表示します。 |
| `/tel company ipo <ID> <銘柄> <初値>` | 代表が組織を一度だけ上場します。 |
| `/tel crypto create <銘柄> <初値>` | **100,000.00** を Vault から支払い、暗号資産を登録します。 |

ショップ平均価格は、購入店・販売店の1個あたりの提示価格から計算します。カスタム名やエンチャントなどのメタデータが異なるアイテムは個別に表示します。

証券市場の価格は流通量に応じて変動します。買うごとに次の価格が上がり、売るごとに下がります。複数口の約定では各口の価格を合算します。購入代金の税引前部分は将来の売却に充てる設計で、発行者の収益には入りません。各銘柄の発行上限は100万口で、初値は 0.01〜10,000.00 です。銘柄コードは株式と暗号資産で共有します。

GUI は既定で **125 ms** を目標に更新します。描画はメインスレッドで行うため、20 TPS 時は通常 2〜3 tick ごとです。`plugins/TownyEconomyLaw/config.yml` の `market-refresh-ms` で変更できます。

## オークション

| コマンド | 操作 |
| --- | --- |
| `/tel auction` | 出品と最低入札額を表示し、左クリックでその金額を入札します。 |
| `/tel auction list <開始価格> <分>` | メインハンドのスタック全体を出品します。1分〜7日。 |
| `/tel auction bid <出品ID> <金額>` | 金額を指定して入札します。 |
| `/tel auction cancel <出品ID>` | 出品者が入札前の出品を取り消します。 |
| `/tel auction claims` / `/tel auction claim <出品ID>` | 落札品、売却代金、入札返金、出品取消品を受け取ります。 |

最高入札額は Vault 残高から預かります。別のプレイヤーが上回ると、前の入札者は受取コマンドから返金を請求できます。出品者と落札者がオフラインでも期限到来時に決済し、受け取りは後から行えます。

## Towny の国と司法

| コマンド | 必要な権限 |
| --- | --- |
| `/tel role grant <プレイヤー> <役職>` / `/tel role revoke <プレイヤー> <役職>` | 国のリーダーまたは大臣。大臣の任命・変更は国のリーダーのみ。 |
| `/tel role mine` | 所属国での自分の役職を表示します。 |
| `/tel law set <ID> <本文>` / `/tel law remove <ID>` | 旧形式の法律を変更します。権限は現在の MDSA に従います。 |
| `/tel law list` / `/tel law show <ID>` | 所属国の住民 |
| `/tel plot set jail` | `warden`。自国の Towny プロット内で実行します。 |
| `/tel plot set courthouse` | `judge`。自国の Towny プロット内で実行します。`courtroom` も使用できます。 |
| `/tel plot set police` / `/tel plot set legislature` | 自国プロットで、それぞれ `policeman` / `politician` が設定できます。 |
| `/tel plot show` | 現在地の司法プロット種別を表示します。 |
| `/tel court` | 所属国の住民が、登録済みの法廷へ移動できます。 |
| `/tel jail arrest <プレイヤー> <分> <法律ID>` | `warden`。オンラインの自国住民が対象。登録した法律を指定します。 |
| `/tel jail release <プレイヤー>` | 拘禁した国の `warden` または `policeman`、または `townyeconomylaw.jail.override`。コンソールでも緊急釈放できます。 |
| `/tel tax set <割合>` | `treasurer`。0.00〜100.00%。 |
| `/tel tax show` / `/tel tax collect` | 所属国の住民は税率・国庫残高を閲覧できます。出金は国のリーダーのみ。 |

国のリーダーと大臣（`minister`）は各担当者の操作を実行できます。役職は Towny の国 ID ごとに管理し、権限チェックでは現在の所属国を参照します。法律の運用は権限を持つプレイヤーが行います。拘禁中は登録プロットに留まり、コマンド、テレポート、攻撃、ブロック破壊・設置が制限されます。期限経過後はその場で制限が解除されます。

証券税は株式・暗号資産の売買ごとに、取引者の所属国の税率を適用します。購入時は加算、売却時は控除します。徴収額はプラグイン内の国庫へ入り、国のリーダーの Vault 残高へ出金できます。

### 政体・法令・選挙

空白を含むタイトル、本文、罰則、追記は項目ごとに二重引用符で囲みます。罰則や追記を省く場合は `-` を使えます。

| コマンド | 操作 |
| --- | --- |
| `/tel job set <MCID> <judge\|prosecutor\|policeman\|politician>` | 国のリーダーまたは大臣が任命します。解除は `/tel role revoke <MCID> <役職>`。 |
| `/tel nation set MDSA <制度>` | 国のリーダーが法令の決定方法を設定します。既定は君主。 |
| `/tel nation set EA <制度>` | 国のリーダーが通常選挙の有権者を設定します。既定は無し。 |
| `/tel nation constitution add <タイトル> <内容> <追記>` | 憲法を登録するか、法案として提出します。 |
| `/tel nation law add <タイトル> <内容> <違反罰則> <追記>` | 法律を登録するか、法案として提出します。 |
| `/tel nation constitution remove <タイトルまたはID>` / `/tel nation law remove <タイトルまたはID>` | 指定の法令の廃止を処理します。 |
| `/tel town regulations add <タイトル> <内容> <違反罰則> <追記>` | 町長が条例を登録します。 |
| `/tel town regulations edit <regulation_id> <タイトル> <内容> <違反罰則> <追記>` | 町長が条例を編集します。 |
| `/tel town regulations remove <タイトルまたはID>` | 町長が条例を削除します。 |
| `/tel constitution GUI` / `/tel law GUI` / `/tel regulations GUI` | 所属先の法令、ID、罰則と追記を閲覧します。 |
| `/tel nation election start <日数> <総選挙\|国事選挙\|議席選挙> <内容>` | 国のリーダーが通常選挙を開始します。 |
| `/tel nation election list` / `/tel nation election show <投票ID>` | 開催中の選挙、候補、議案、結果を確認します。 |
| `/tel nation election stand <投票ID>` | 総選挙または議席選挙に立候補します。 |
| `/tel nation election vote <投票ID> <yes\|no\|候補者MCID>` | 国事選挙と法案は賛否、総選挙と議席選挙は候補者に投票します。 |

MDSA の君主は国のリーダー、一党はリーダーと同じ政党に所属するプレイヤーが直接決定します。民主は国民の賛否投票、限定民主は政治家の議会議決です。君民主と限定民主と君主では、対応する投票に加えて国のリーダーも直接決定できます。MDSA による法案投票では、EA の設定にかかわらず国民または政治家が決定します。

EA の無しでは通常選挙の投票権がなく、政党では政党所属の政治家、限定民主では政治家、民主では国民が投票できます。有権者は開始時に確定し、投票時にも所属国を確認します。期限は開始したワールドの経過 tick で測ります。法案は1マイクラ日で締め切り、賛成が反対を上回ると可決・適用されます。最多得票が同数の場合は当選者なしです。議席選挙の当選者は政治家に任命され、総選挙は当選結果を記録します。

司法プロット種別はこのプラグインが管理します。同種のプロットを複数設定した場合、牢獄移送と `/tel court` は最後に登録した地点を利用します。警察官は拘禁・釈放を行えます。検察官は任命・表示でき、法令の違反罰則は閲覧用の記録として使います。

### 政党・傀儡国・自治体

| コマンド | 操作 |
| --- | --- |
| `/tel political_party create <政党名> <コード> [MCID,MCID]` | 国内で政党を結成します。コードは大文字英字1〜4文字。初期メンバーは招待を受諾して参加します。 |
| `/tel political_party add <MCID>` | 政党代表が同じ国のプレイヤーを招待します。`potilical_party` も使えます。 |
| `/tel political_party join <政党名またはコード>` / `/tel political_party leave` | 招待を受諾するか、所属政党から脱退します。 |
| `/tel political_party remove <政党名またはコード>` / `/tel political_party GUI` | 政党代表が解散するか、国内の政党を閲覧します。 |
| `/tel plot puppet create <国名> <MCID>` | 親国のリーダーが、無所属の町の町長を首都のリーダーとして別の Towny 国を設立します。 |
| `/tel plot puppet add <国名>` / `/tel nation puppet join <親国名>` | 親国が既存国を招待し、招待された国のリーダーが受諾します。 |
| `/tel plot puppet annex <傀儡国名>` | 親国のリーダーが、指定した直接の傀儡国の**現在地の1プロット**を親国の首都に移管します。町の中心・前哨地・私有地・グループ所属地は対象外です。 |
| `/tel plot decentralization create <町名> <MCID>` | 町に未所属でオンラインの対象者の現在地で町を設立し、親国の自治体にします。Towny の土地条件が適用されます。 |
| `/tel plot decentralization add <町名>` | 親国のリーダーが自国の既存町を自治体に登録します。 |
| `/tel nation puppet rebel` / `/tel plot decentralization rebel` | 傀儡国のリーダー・自治体の町長が独立要求を記録します。 |
| `/tel nation puppet release <国名>` / `/tel plot decentralization remove <町名>` | 親国のリーダーが離脱を承認するか、親国の政治家が議会に付議します。 |
| `/tel nation puppet status` | 自国に関連する傀儡国・自治体と独立要求を確認します。 |

傀儡国の独立は従属関係を解除し、自治体の離脱は町を Towny の国から外します。首都は離脱できません。Towny の通常の離脱・追放操作でも登録済み自治体に対して承認手続きが適用されます。

## 銀行

| コマンド | 操作 |
| --- | --- |
| `/tel bank create <ID> <名前>` / `/tel bank list` | 銀行を開設するか、銀行の一覧を表示します。 |
| `/tel bank info <ID>` | 銀行の入金手数料と自分の預金残高を表示します。 |
| `/tel bank deposit <ID> <金額>` / `/tel bank withdraw <ID> <金額>` | Vault と銀行の間で自分の資金を入出金します。 |
| `/tel bank fee <ID> <割合>` / `/tel bank collect <ID>` | 銀行所有者が入金手数料（0〜10%）を設定し、確定した手数料を受け取れます。 |

預金残高は預金者ごとに管理され、銀行所有者が出金できるのは手数料収益です。

## 保存と権限

ID と銘柄コードは半角英数字・アンダースコア・ハイフンで2〜24文字です。コマンドの基本権限 `townyeconomylaw.use` は既定で全員、緊急釈放の権限は既定で OP です。

状態は `plugins/TownyEconomyLaw/state.yml` に保存します。書き込みエラー時は、実行済みの Vault 操作やインベントリ変更の取り消しを試みます。サーバーが強制終了した場合は Vault・プレイヤーデータと状態ファイルの保存にずれが生じる可能性があります。バックアップはサーバーを停止してから各データをまとめて取得してください。
