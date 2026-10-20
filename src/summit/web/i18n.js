// Interface text in English and Japanese. Only UI chrome is translated; library data (titles, artists, cluster
// names from Discogs styles, tag names) stays as-is. Loaded by the host after core.js; frames use window.parent.Summit.
(function () {
  const H = window.Summit;
  const STR = {
    // shell
    'mode.map': ['Map', 'マップ'], 'mode.tag': ['Tag', 'タグ'], 'mode.flow': ['Flow', 'フロー'],
    color: ['Color', 'カラー'], labels: ['Labels', 'ラベル'], genres: ['Genres', 'ジャンル'], subgenres: ['Subgenres', 'サブジャンル'],
    'color.cluster': ['Sub-cluster', 'サブクラスター'], 'color.family': ['Family', 'ファミリー'], 'color.bpm': ['BPM', 'BPM'],
    'color.tagged': ['Tagged vs untagged', 'タグ付き / なし'],
    'cat.Mood': ['Mood', 'ムード'], 'cat.Energy': ['Energy', 'エナジー'], 'cat.Texture': ['Texture', 'テクスチャ'],
    'cat.Situation': ['Situation', 'シーン'], 'cat.Vibe': ['Vibe', 'バイブ'],
    searchPh: ['Search a vibe or a name: “rainy late night”, “acid”, “Daphni”…', '雰囲気や名前で検索:「rainy late night」「acid」「Daphni」…'],
    shellHelp: ['Drag pans · scroll zooms · <kbd>ctrl</kbd>+drag lassos (<kbd>⇧</kbd> adds) · <kbd>esc</kbd> clears · <kbd>space</kbd> play · double-click a track to go to it · <kbd>⌘[</kbd> back',
                'ドラッグで移動 · スクロールでズーム · <kbd>ctrl</kbd>+ドラッグで投げ縄（<kbd>⇧</kbd>で追加）· <kbd>esc</kbd>で解除 · <kbd>space</kbd>で再生 · 曲をダブルクリックでその曲へ移動 · <kbd>⌘[</kbd>で戻る'],
    backTip: ['Back (⌘[ or ⌥←)', '戻る (⌘[ または ⌥←)'], fwdTip: ['Forward (⌘] or ⌥→)', '進む (⌘] または ⌥→)'],
    // library updates
    library: ['Library', 'ライブラリ'], libOk: ['Library ✓', 'ライブラリ ✓'], libTodo: ['{n} to analyze', '未解析 {n} 曲'], libNewFiles: ['{n} new files', '新しいファイル {n} 件'],
    libUpdated: ['Library updated: reload', '更新完了: 再読み込み'],
    libNew: ['New or changed files', '新規・変更ファイル'], libUnanalyzed: ['Scanned, not analyzed', 'スキャン済み・未解析'],
    libUnplaced: ['Analyzed, not on the map yet', '解析済み・マップ未配置'], libFailed: ['Couldn’t be read', '読み込めなかった曲'],
    'upd.scan': ['Scanning', 'スキャン中'], 'upd.analyze': ['Analyzing', '解析中'], 'upd.layout': ['Laying out map', 'マップを配置中'],
    'upd.label': ['Naming groups', 'グループに命名中'], 'upd.done': ['Finishing', '完了処理中'],
    updStart: ['Analyze & add to map', '解析してマップに追加'], recheck: ['Check again', '再確認'], stop: ['Stop', '停止'], reload: ['Reload', '再読み込み'],
    updHint: ['Scans the library, analyzes new tracks, then redraws the map. The map’s layout shifts; renamed groups keep their names.',
              'ライブラリをスキャンし、新しい曲を解析してからマップを描き直します。配置は変わりますが、名前を変えたグループはその名前のままです。'],
    updStopHint: ['Safe to stop: the next run picks up where this one left off.', '停止しても大丈夫です: 次回は途中から再開します。'],
    updDone: ['Done. Reload to see the new tracks (playback stops).', '完了しました。再読み込みすると新しい曲が表示されます（再生は止まります）。'],
    updFailed: ['The update stopped with an error:', 'エラーで更新が止まりました:'], updStopped: ['Stopped. Run it again to continue.', '停止しました。もう一度実行すると続きから再開します。'],
    loading: ['Loading library', 'ライブラリを読み込み中'],
    noTracks: ['No analyzed tracks yet: run summit analyze', '解析済みの曲がまだありません: summit analyze を実行してください'],
    helpOn: ['Help on', 'ヘルプ表示'], helpOff: ['Help off', 'ヘルプ非表示'],
    // player
    clickToPlay: ['Click any track to play it', '曲をクリックして再生'], queue: ['Queue', 'キュー'], seek: ['Click to seek', 'クリックでシーク'],
    prev: ['Previous (⇧←)', '前へ (⇧←)'], playPause: ['Play / pause (space)', '再生 / 一時停止 (space)'], next: ['Next (⇧→)', '次へ (⇧→)'],
    like: ['Like', 'お気に入り'], similarTo: ['Similar to {x}', '「{x}」に似た曲'], liked: ['Liked', 'お気に入り'], flow: ['Flow', 'フロー'],
    audition: ['Audition', '試聴'], searchPreview: ['Search preview', '検索プレビュー'],
    play: ['▶ Play', '▶ 再生'], pause: ['❚❚ Pause', '❚❚ 一時停止'],
    goToTip: ['Double-click to go to this track on the map', 'ダブルクリックでマップ上のこの曲へ移動'],
    rowTip: ['Click to play · double-click to go to it', 'クリックで再生 · ダブルクリックでその曲へ移動'],
    // playlists
    addToPl: ['＋ Add to playlist', '＋ プレイリストに追加'], addToN: ['Add {n} to…', '{n} 曲を追加…'], newPlaylistDots: ['＋ New playlist…', '＋ 新しいプレイリスト…'],
    addedTo: ['Added {n} to {x}', '{x} に {n} 曲を追加しました'], alreadyIn: ['Already in {x}', 'すでに {x} に入っています'],
    removeFromPl: ['Remove from playlist', 'プレイリストから外す'], dragToReorder: ['· drag to reorder', '· ドラッグで並べ替え'],
    // common
    tracks: ['Tracks', '曲数'], untagged: ['Untagged', 'タグなし'], length: ['Length', '長さ'], savePlaylist: ['Save as playlist', 'プレイリストに保存'],
    playlists: ['Playlists', 'プレイリスト'], playlistName: ['Playlist name', 'プレイリスト名'], newPlaylist: ['New playlist', '新しいプレイリスト'],
    saved: ['Saved “{x}”', '「{x}」を保存しました'], savedN: ['Saved “{x}” · {n} tracks', '「{x}」を保存しました · {n} 曲'],
    nTracks: ['{n} tracks', '{n} 曲'], lasso: ['Lasso selection', '投げ縄選択'], search: ['Search: “{x}”', '検索:「{x}」'], allTracks: ['All tracks', 'すべての曲'],
    title: ['Title', 'タイトル'], artist: ['Artist', 'アーティスト'], key: ['Key', 'キー'], cluster: ['Cluster', 'クラスター'],
    tags: ['Tags', 'タグ'], suggested: ['Suggested', '候補'], start: ['Start', '開始'], preview: ['Preview', 'プレビュー'],
    taggedN: ['Tagged {n} tracks “{x}”', '{n} 曲に「{x}」をタグ付けしました'], retrained: [' · classifier retrained', ' · 分類器を再学習'],
    // map mode
    'tab.track': ['Track', '曲'], 'tab.selection': ['Selection', '選択'], 'tab.playlists': ['Playlists', 'プレイリスト'], 'tab.liked': ['Liked', 'お気に入り'],
    mapHelp: ['<b class="kicker">Map</b><br>Click a dot to play it and see details; double-click a dot or row to go to it. <kbd>⌥</kbd>+hover auditions. <kbd>ctrl</kbd>+drag lassos a region (<kbd>⇧</kbd> adds); <kbd>esc</kbd> clears. Rows play in order, so next / previous follow the list. Click a family or group name to rename it.',
              '<b class="kicker">マップ</b><br>点をクリックすると再生して詳細を表示。点や行をダブルクリックでその曲へ移動。<kbd>⌥</kbd>+ホバーで試聴。<kbd>ctrl</kbd>+ドラッグで範囲を投げ縄選択（<kbd>⇧</kbd>で追加）、<kbd>esc</kbd>で解除。リストは順番に再生され、前へ / 次へはリストに従います。ファミリー名やグループ名をクリックすると名前を変更できます。'],
    clickDot: ['Click a dot on the map', 'マップ上の点をクリック'], nothingAbove: ['Nothing above threshold', 'しきい値を超える候補なし'],
    styles: ['Discogs styles', 'Discogs スタイル'], soundsLike: ['Sounds like', '似ている曲'],
    lassoOrSearch: ['Lasso a region or search', '範囲を投げ縄で選ぶか、検索してください'], teachVibe: ['Teach a vibe…', 'バイブを教える…'], clear: ['Clear', 'クリア'],
    commonTags: ['Common tags: apply to all', '共通タグ: すべてに適用'], all: ['✓ all', '✓ 全部'], tagAllN: ['Tag all {n}', '{n} 曲すべてにタグ付け'],
    clusters: ['Clusters', 'クラスター'], andMore: ['…and {n} more', '…ほか {n} 曲'],
    flowKind: [' · flow', ' · フロー'], show: ['Show', '表示'], noPlaylists: ['No playlists yet: lasso a region and save it, or use ＋ on any track', 'プレイリストはまだありません: 範囲を選んで保存するか、曲の ＋ を使ってください'],
    exportRb: ['Export to rekordbox', 'rekordbox に書き出し'],
    exportHint: ['Writes data/exports/summit.xml (a summit/ playlist folder) plus one .m3u8 per playlist.', 'data/exports/summit.xml（summit/ プレイリストフォルダ）と、プレイリストごとの .m3u8 を書き出します。'],
    noLiked: ['No liked songs yet: tap ♥ on any song', 'お気に入りはまだありません: 曲の ♥ をタップしてください'], playAll: ['▶ Play all', '▶ すべて再生'], recentFirst: ['Most recent first', '新しい順'],
    vibePrompt: ['Name this vibe: it becomes a tag trained on these tracks', 'このバイブに名前を付けてください。これらの曲で学習したタグになります'],
    vibeTrained: ['Vibe “{x}” trained on {n} tracks', 'バイブ「{x}」を {n} 曲で学習しました'],
    deletePl: ['Delete playlist “{x}”?', 'プレイリスト「{x}」を削除しますか？'],
    exported: ['Exported {p} playlists · {n} tracks → summit.xml', '{p} 件のプレイリスト · {n} 曲を summit.xml に書き出しました'],
    familyRenamed: ['Family renamed', 'ファミリー名を変更しました'], groupRenamed: ['Group renamed', 'グループ名を変更しました'],
    // tag mode
    untaggedOnly: ['Untagged only', 'タグなしのみ'], confirmTop: ['Confirm top suggestion', '最有力候補を確定'], addTag: ['Add tag…', 'タグを追加…'],
    nTicked: ['{n} ticked', '{n} 件選択'], showing300: [' (showing 300)', '（300 曲を表示）'], noneYet: ['None yet', 'まだありません'],
    confirmedN: ['Confirmed tags on {n} tracks', '{n} 曲のタグを確定しました'],
    tagHelp: ['<b class="kicker">Tag</b><br>Colour starts as tagged (green) vs untagged. Pick a tag on the left to see its strongest candidates. <kbd>ctrl</kbd>+drag lassos a region into the table. Tick rows to confirm in bulk. A tag with 5+ confirmed tracks retrains on your picks.',
              '<b class="kicker">タグ</b><br>最初はタグ付き（緑）とタグなしで色分けされます。左のタグを選ぶと有力な候補が表示されます。<kbd>ctrl</kbd>+ドラッグで範囲を投げ縄選択して表に表示。行にチェックを入れてまとめて確定。確定した曲が 5 曲以上になると、そのタグはあなたの選択で再学習されます。'],
    // flow mode
    newFlow: ['＋ New flow', '＋ 新しいフロー'], undo: ['Undo (⌘Z)', '元に戻す (⌘Z)'], redo: ['Redo (⇧⌘Z)', 'やり直す (⇧⌘Z)'],
    undoX: ['Undo {x} (⌘Z)', '{x} を元に戻す (⌘Z)'], nothingUndo: ['Nothing to undo', '元に戻す操作はありません'],
    radius: ['Radius', '半径'], radiusTip: ['How far from the route a track can be to get pulled in', 'ルートからどれだけ離れた曲まで取り込むか'],
    lengthTip: ['Target set length', 'セットの目標の長さ'], inCorridor: ['{n} in corridor', 'コリドー内 {n} 曲'], inCorridorStat: ['In corridor', 'コリドー内'],
    shuffle: ['⤨ Shuffle', '⤨ シャッフル'], shuffleTip: ['Re-roll which corridor tracks get picked', 'コリドーから選ぶ曲を選び直す'],
    savedFlows: ['Saved flows…', '保存したフロー…'], savedFlowsTip: ['Open a saved flow', '保存したフローを開く'], save: ['Save', '保存'],
    hint0: ['<b>Click the map</b> to place the first stage', '<b>マップをクリック</b>して最初のステージを置く'],
    hint1: ['<b>Click again</b> to place the next stage. The route connects them in order.', '<b>もう一度クリック</b>して次のステージを置く。ルートは順番につながります。'],
    hint2: ['<b>Click</b> adds a stage · <b>click the route</b> inserts · <b>drag</b> moves · select + <kbd>⌫</kbd> deletes · <kbd>⌘Z</kbd> undo',
            '<b>クリック</b>でステージ追加 · <b>ルートをクリック</b>で挿入 · <b>ドラッグ</b>で移動 · 選択して <kbd>⌫</kbd> で削除 · <kbd>⌘Z</kbd> で元に戻す'],
    short: ['Short: widen radius', '不足: 半径を広げる'], noStages: ['No stages yet', 'ステージはまだありません'], playFlow: ['▶ Play flow', '▶ フローを再生'],
    deleteStage: ['Delete stage', 'ステージを削除'], timelineEmpty: ['The set timeline appears here as you place stages', 'ステージを置くと、ここにセットのタイムラインが表示されます'],
    listEmpty: ['Place two stages on the map to pull in tracks along the route', 'マップに 2 つのステージを置くと、ルート沿いの曲が集まります'],
    removeTrack: ['Remove from flow (next best track fills in)', 'フローから外す（次の候補が入ります）'],
    newFlowToast: ['New flow: click the map to place stage 1', '新しいフロー: マップをクリックしてステージ 1 を置いてください'],
    saveFlowPrompt: ['Save flow as playlist', 'フローをプレイリストとして保存'], flowName: ['Flow {x}', 'フロー {x}'],
    matches: ['{n} matches highlighted: click one to add it as a stage', '{n} 件をハイライト: クリックするとステージに追加されます'],
    palPh: ['artist, title or a vibe like “sunrise euphoric”…', 'アーティスト、タイトル、または「sunrise euphoric」のような雰囲気…'],
    palHint: ['Type and press Enter. Click a result to add it as the next stage; ▶ previews it.', '入力して Enter。結果をクリックすると次のステージに追加、▶ でプレビュー。'],
    undid: ['Undo: {x}', '元に戻しました: {x}'], redid: ['Redo: {x}', 'やり直しました: {x}'],
    'op.add': ['add stage', 'ステージの追加'], 'op.insert': ['insert stage', 'ステージの挿入'], 'op.move': ['move stage', 'ステージの移動'],
    'op.delete': ['delete stage', 'ステージの削除'], 'op.remove': ['remove track', '曲の除外'], 'op.shuffle': ['shuffle picks', 'シャッフル'],
    'op.new': ['new flow', '新しいフロー'], 'op.open': ['open flow', 'フローを開く'], 'op.radius': ['change radius', '半径の変更'], 'op.length': ['change length', '長さの変更'],
    plChangedElsewhere: ['This playlist changed on another device: showing the latest version', 'このプレイリストは別のデバイスで変更されました: 最新の内容を表示しています'],
    v3dTip: ['3D map (drag to orbit, shift+drag to pan)', '3D マップ（ドラッグで回転、shift+ドラッグで移動）'],
    // tree mode
    'mode.tree': ['Tree', 'ツリー'], explore: ['⑂ Explore similar', '⑂ 似た曲をたどる'], exploreTip: ['Start a tree from this track in Tree mode', 'ツリーモードでこの曲からツリーを始める'],
    treeSeed: ['Start from a song', '曲から始める'], treeSeedPh: ['Search a name or a vibe…', '名前や雰囲気で検索…'],
    treeNowPlaying: ['＋ Now playing', '＋ 再生中の曲'], treeNothingPlaying: ['Nothing is playing', '再生中の曲はありません'],
    treeClear: ['Clear tree', 'ツリーをクリア'], treeClearConfirm: ['Clear the whole tree? (The playlist stays.)', 'ツリーをすべてクリアしますか？（プレイリストは残ります）'],
    treeBranch: ['Branch', '分岐数'], treeBranchTip: ['How many similar tracks each song opens', '1 曲から開く似た曲の数'], treeFit: ['Fit', '全体'],
    treeAdd: ['Add to playlist (Enter)', 'プレイリストに追加 (Enter)'], treeExpand: ['Open similar tracks (→)', '似た曲を開く (→)'], treeCollapse: ['Close (←)', '閉じる (←)'],
    treeRemove: ['Remove from the tree', 'ツリーから外す'], treeMore: ['＋ more like this', '＋ さらに似た曲'],
    treeCardTip: ['Click to play · double-click to go to it on the map · drag onto the playlist', 'クリックで再生 · ダブルクリックでマップ上へ移動 · プレイリストへドラッグ'],
    treeJumpTip: ['Click to jump to it in the tree · double-click to go to it on the map', 'クリックでツリー内のこの曲へ · ダブルクリックでマップ上へ移動'],
    treePlRowTip: ['Click to play (and find it in the tree) · drag to reorder', 'クリックで再生（ツリー内で表示）· ドラッグで並べ替え'],
    treeEmpty: ['Search for a song on the left (or use ＋ Now playing) to start a tree. Each song opens its most similar tracks; open those to keep exploring.',
                '左で曲を検索するか（または ＋ 再生中の曲）、ツリーを始めましょう。曲ごとに最も似た曲が開き、さらに開いて探索を続けられます。'],
    treeOutlineEmpty: ['Songs in the tree appear here', 'ツリー内の曲がここに表示されます'],
    treePlaylist: ['Playlist', 'プレイリスト'], treePickPl: ['Choose a playlist…', 'プレイリストを選択…'],
    treeNoPl: ['Choose a playlist above, or just press ＋ on a song to start a new one', '上でプレイリストを選ぶか、曲の ＋ を押して新しく作成'],
    treePlEmpty: ['Press ＋ on songs in the tree, or drag them here', 'ツリーの曲の ＋ を押すか、ここにドラッグ'],
    treeHelp: ['<b class="kicker">Tree</b><br>Click a song to play it; ＋ adds it to the playlist (added songs grey out); › opens its similar songs. Drag the background or scroll to pan, pinch or <kbd>⌘</kbd>+scroll to zoom. Arrow keys walk the tree, <kbd>Enter</kbd> adds.',
               '<b class="kicker">ツリー</b><br>曲をクリックで再生、＋ でプレイリストに追加（追加済みはグレー表示）、› で似た曲を開く。背景をドラッグかスクロールで移動、ピンチか <kbd>⌘</kbd>+スクロールでズーム。矢印キーでツリーを移動、<kbd>Enter</kbd> で追加。'],
  };

  try { H.lang = localStorage.getItem('summit.lang') || 'en'; } catch (e) { H.lang = 'en'; }
  H.t = (key, vars = {}) => {
    const e = STR[key];
    let s = e ? e[H.lang === 'ja' ? 1 : 0] : key;
    for (const [k, v] of Object.entries(vars)) s = s.replaceAll(`{${k}}`, v);
    return s;
  };
  // Static markup: data-i18n (text), data-i18n-html, data-i18n-ph (placeholder), data-i18n-title.
  H.applyLang = doc => {
    if (!doc || !doc.documentElement) return;
    doc.documentElement.lang = H.lang;
    doc.querySelectorAll('[data-i18n]').forEach(el => (el.textContent = H.t(el.dataset.i18n)));
    doc.querySelectorAll('[data-i18n-html]').forEach(el => (el.innerHTML = H.t(el.dataset.i18nHtml)));
    doc.querySelectorAll('[data-i18n-ph]').forEach(el => (el.placeholder = H.t(el.dataset.i18nPh)));
    doc.querySelectorAll('[data-i18n-title]').forEach(el => (el.title = H.t(el.dataset.i18nTitle)));
  };
  H.setLang = lang => {
    H.lang = lang;
    try { localStorage.setItem('summit.lang', lang); } catch (e) {}
    H.applyLang(document);
    document.querySelectorAll('iframe').forEach(f => { try { H.applyLang(f.contentDocument); } catch (e) {} });
    H.emit('lang', lang);
  };
})();
