import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SpringLayout;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;

//集計後のプレイヤーごと
class PlayerStat {
	String name; // for user identication
	Set<Match> matches = new HashSet<Match>(); // 参加match。
	int totalScore;
	int participationCount; // rate 分母。round または match 数。RankingMaker による。
	int winCount; // rate 分子。優勝やクリアなど。RankingMaker による。
	Map<String, String> additional = new HashMap<String, String>(); // 独自の統計を使う場合用領域

	public PlayerStat(String name) {
		this.name = name;
	}

	public double getRate() {
		return Core.calRate(winCount, participationCount);
	}

	public int getIntAdditional(String key) {
		String v = additional.get(key);
		try {
			return Integer.parseInt(v);
		} catch (Exception e) {
		}
		return 0;
	}

	public String toString() {
		return name;
	}
}

//各ラウンドのプレイヤー戦績
class Player {
	String name; // for user identication
	int id; // id of current round (diferrent for each rounds)
	int objectId; // object id of current round (for score log)
	int squadId;
	int partyId;
	int ranking; // rank of current round

	Boolean qualified;
	int score; // ラウンド中のスコアあるいは順位スコア
	int finalScore; // ラウンド終了後に出力されたスコア

	Player(String name, int id, int squadId, int partyId) {
		this.name = name;
		this.id = id;
		this.squadId = squadId;
		this.partyId = partyId;
	}

	public String toString() {
		return name;
	}
}

class Squad {
	int squadId;
	List<Player> members = new ArrayList<Player>();

	public Squad(int id) {
		squadId = id;
	}

	public int getScore() {
		int score = 0;
		for (Player p : members) {
			if (p.finalScore > 0)
				score += p.finalScore;
			else
				score += p.score;
		}
		return score;
	}
}

class Round {
	Match match;
	boolean fixed; // ステージ完了まで読み込み済み
	boolean isFinal;
	String name;
	long id; // 過去に読み込んだステージであるかの判定用。厳密ではないが frame 数なら衝突確率は低い。start 値で良いかも。
	Date start;
	Date end;
	Map<String, Player> byName = new HashMap<String, Player>();
	Map<Integer, Player> byId = new HashMap<Integer, Player>();

	public Round(String name, long id, boolean isFinal, Match match) {
		this.name = name;
		this.id = id;
		this.isFinal = isFinal;
		this.match = match;
	}

	public void add(String name, int id, int squadId, int partyId) {
		synchronized (Core.listLock) {
			Player p = new Player(name, id, squadId, partyId);
			byId.put(p.id, p);
			byName.put(p.name, p);
		}
	}

	public void remove(String name) {
		synchronized (Core.listLock) {
			Player p = byName.get(name);
			if (p == null)
				return;
			byName.remove(name);
			byId.remove(p.id);
		}
	}

	public Player getByObjectId(int id) {
		for (Player p : byId.values())
			if (p.objectId == id)
				return p;
		return null;
	}

	public int getPlayerCount() {
		return byId.size();
	}

	public ArrayList<Player> byRank() {
		ArrayList<Player> list = new ArrayList<Player>(byName.values());
		Collections.sort(list, new Core.PlayerComparator());
		return list;
	}

	public ArrayList<Squad> bySquadRank() {
		if (byId.size() == 0 || byId.values().iterator().next().squadId == 0)
			return null;
		Map<Integer, Squad> bySquadId = new HashMap<Integer, Squad>();
		for (Player p : byId.values()) {
			Squad s = bySquadId.get(p.squadId);
			if (s == null) {
				s = new Squad(p.squadId);
				bySquadId.put(s.squadId, s);
			}
			s.members.add(p);
		}
		ArrayList<Squad> list = new ArrayList<Squad>(bySquadId.values());
		for (Squad s : list)
			Collections.sort(s.members, new Core.PlayerComparator());
		Collections.sort(list, new Comparator<Squad>() {
			@Override
			public int compare(Squad s1, Squad s2) {
				return (int) Math.signum(s2.getScore() - s1.getScore());
			}
		});
		return list;
	}

	public Squad getSquad(int squadId) {
		Squad s = new Squad(squadId);
		for (Player p : byId.values())
			if (p.squadId == squadId)
				s.members.add(p);
		return s;
	}
}

// 一つのショー
class Match {
	boolean fixed; // 完了まで読み込み済み
	String name;
	long id; // 過去に読み込んだステージであるかの判定用。仮。start 値で良いかも。
	Date start;
	Date end;
	List<Round> rounds = new ArrayList<Round>();

	public Match(String name, long id) {
		this.name = name;
		this.id = id;
	}
}

enum RoundType {
	RACE, HUNT, SURVIVAL, LOGIC, TEAM
};

class RoundDef {

	public final String dispName;
	public final String dispNameJa;
	public final RoundType type;

	public RoundDef(String name, String nameJa, RoundType type) {
		dispName = name;
		dispNameJa = nameJa;
		this.type = type;
	}

	static Map<String, RoundDef> roundNames = new HashMap<String, RoundDef>();
	static {
		roundNames.put("FallGuy_Airtime", new RoundDef("Airtime", "エアータイム", RoundType.HUNT));
		roundNames.put("FallGuy_BiggestFan", new RoundDef("Big Fans", "ビッグファン", RoundType.RACE));
		roundNames.put("FallGuy_KingOfTheHill2", new RoundDef("Bubble Trouble", "バブルトラブル", RoundType.HUNT));
		roundNames.put("FallGuy_1v1_ButtonBasher", new RoundDef("Button Bashers", "ボタンバッシャーズ", RoundType.TEAM));
		roundNames.put("FallGuy_DoorDash", new RoundDef("Door Dash", "ドアダッシュ", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_02_01", new RoundDef("Dizzy Heights", "スピンレース", RoundType.RACE));
		roundNames.put("FallGuy_IceClimb_01", new RoundDef("Freezy Peak", "スノーマウンテン", RoundType.RACE));
		roundNames.put("FallGuy_DodgeFall", new RoundDef("Fruit Chute", "フルーツパニック", RoundType.RACE));
		roundNames.put("FallGuy_SeeSaw360", new RoundDef("Full Tilt", "フルティルト", RoundType.RACE));
		roundNames.put("FallGuy_ChompChomp_01", new RoundDef("Gate Crash", "ゲートクラッシュ", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_01", new RoundDef("Hit Parade", "ヒットパレード", RoundType.RACE));
		roundNames.put("FallGuy_Hoops_Blockade", new RoundDef("Hoopsie Legends", "フープループレジェンド", RoundType.HUNT));
		roundNames.put("FallGuy_Gauntlet_04", new RoundDef("Knight Fever", "ナイト・フィーバー", RoundType.RACE));
		roundNames.put("FallGuy_FollowTheLeader", new RoundDef("Leading Light", "動くスポットライト", RoundType.HUNT));
		roundNames.put("FallGuy_DrumTop", new RoundDef("Lily Leapers", "リリー・リーパー", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_08", new RoundDef("Party Promenade", "パーティープロムナード", RoundType.RACE));
		roundNames.put("FallGuy_Penguin_Solos", new RoundDef("Pegwin Party", "ペンギンプールパーティー", RoundType.HUNT));
		roundNames.put("FallGuy_PipedUp", new RoundDef("Pipe Dream", "パイプドリーム", RoundType.RACE));
		roundNames.put("FallGuy_Tunnel_Race_01", new RoundDef("Roll On", "ロールオン", RoundType.RACE));
		roundNames.put("FallGuy_SeeSaw_variant2", new RoundDef("See Saw", "シーソーゲーム", RoundType.RACE));
		roundNames.put("FallGuy_ShortCircuit", new RoundDef("Short Circuit", "ショート・サーキット", RoundType.RACE));
		roundNames.put("FallGuy_SkeeFall", new RoundDef("Ski Fall", "スキーフォール", RoundType.HUNT));
		roundNames.put("FallGuy_Gauntlet_06", new RoundDef("Skyline Stumble", "スカイラインスタンブル", RoundType.RACE));
		roundNames.put("FallGuy_Lava_02", new RoundDef("Slime Climb", "スライムクライム", RoundType.RACE));
		roundNames.put("FallGuy_SlimeClimb_2", new RoundDef("Slimescraper", "スライムスクレイパー", RoundType.RACE));
		roundNames.put("FallGuy_TipToe", new RoundDef("Tip Toe", "ヒヤヒヤロード", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_07", new RoundDef("Treetop Tumble", "ツリートップタンブル", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_05", new RoundDef("Tundra Run", "ツンドラダッシュ", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_03", new RoundDef("Whirlygig", "グルグルファイト", RoundType.RACE));
		roundNames.put("FallGuy_WallGuys", new RoundDef("Wall Guys", "ウォールガイズ", RoundType.RACE));
		roundNames.put("FallGuy_FruitPunch", new RoundDef("Big Shots", "ビッグショット", RoundType.SURVIVAL));
		roundNames.put("FallGuy_Block_Party", new RoundDef("Block Party", "ブロックパーティー", RoundType.SURVIVAL));
		roundNames.put("FallGuy_HoverboardSurvival",
				new RoundDef("Hoverboard Heroes", "ホバーボード・ヒーローズ", RoundType.SURVIVAL));
		roundNames.put("FallGuy_JumpClub_01", new RoundDef("Jump Club", "ジャンプクラブ", RoundType.SURVIVAL));
		roundNames.put("FallGuy_MatchFall", new RoundDef("Perfect Match", "パーフェクトマッチ", RoundType.LOGIC));
		roundNames.put("FallGuy_Tunnel_01", new RoundDef("Roll Out", "ロールアウト", RoundType.SURVIVAL));
		roundNames.put("FallGuy_SnowballSurvival", new RoundDef("Snowball Survival", "雪玉サバイバル", RoundType.SURVIVAL));
		roundNames.put("FallGuy_RobotRampage_Arena2",
				new RoundDef("Stompin' Ground", "ストンピングラウンド", RoundType.SURVIVAL));
		roundNames.put("FallGuy_FruitBowl", new RoundDef("Sum Fruit", "カウントフルーツ", RoundType.LOGIC));
		roundNames.put("FallGuy_TailTag_2", new RoundDef("Tail Tag", "しっぽオニ", RoundType.HUNT));
		roundNames.put("FallGuy_Basketfall_01", new RoundDef("Basketfall", "バスケットフォール", RoundType.TEAM));
		roundNames.put("FallGuy_EggGrab", new RoundDef("Egg Scramble", "エッグ・スクランブル", RoundType.TEAM));
		roundNames.put("FallGuy_EggGrab_02", new RoundDef("Egg Siege", "エッグ・キャッスル", RoundType.TEAM));
		roundNames.put("FallGuy_FallBall_5", new RoundDef("Fall Ball", "フォールボール", RoundType.TEAM));
		roundNames.put("FallGuy_BallHogs_01", new RoundDef("Hoarders", "ためこみ合戦", RoundType.TEAM));
		roundNames.put("FallGuy_Hoops_01", new RoundDef("Hoopsie Daisy", "フープ・ループ・ゴール", RoundType.TEAM));
		roundNames.put("FallGuy_TeamInfected", new RoundDef("Jinxed", "バッドラック", RoundType.TEAM));
		roundNames.put("FallGuy_ChickenChase_01", new RoundDef("Pegwin Pursuit", "ペンギンチェイス", RoundType.TEAM));
		roundNames.put("FallGuy_TerritoryControl_v2", new RoundDef("Power Trip", "パワートリップ", RoundType.TEAM));
		roundNames.put("FallGuy_RocknRoll", new RoundDef("Rock'N'Roll", "ロックンロール", RoundType.TEAM));
		roundNames.put("FallGuy_Snowy_Scrap", new RoundDef("Snowy Scrap", "スノースクラップ", RoundType.TEAM));
		roundNames.put("FallGuy_Invisibeans", new RoundDef("Sweet Thieves", "キャンディードロボー", RoundType.TEAM));
		roundNames.put("FallGuy_ConveyorArena_01", new RoundDef("Team Tail Tag", "チームしっぽオニ", RoundType.TEAM));
		roundNames.put("FallGuy_FallMountain_Hub_Complete", new RoundDef("Fall Mountain", "フォールマウンテン", RoundType.RACE));
		roundNames.put("FallGuy_FloorFall", new RoundDef("Hex-A-Gone", "とまるなキケン", RoundType.SURVIVAL));
		roundNames.put("FallGuy_JumpShowdown_01", new RoundDef("Jump Showdown", "ジャンプ・ショーダウン", RoundType.SURVIVAL));
		roundNames.put("FallGuy_Crown_Maze_Topdown", new RoundDef("Lost Temple", "ロストテンプル", RoundType.RACE));
		roundNames.put("FallGuy_Tunnel_Final", new RoundDef("Roll Off", "ロールオフ", RoundType.SURVIVAL));
		roundNames.put("FallGuy_Arena_01", new RoundDef("Royal Fumble", "ロイヤルファンブル", RoundType.HUNT));
		roundNames.put("FallGuy_ThinIce", new RoundDef("Thin Ice", "パキパキアイス", RoundType.SURVIVAL));
	}

	public static RoundDef get(String name) {
		return roundNames.get(name);
	}
}

// 優勝回数ランキングをとりあえずデフォルト実装
class RankingMaker {
	public String toString() {
		return "Final/Win";
	}

	public String getDesc() {
		return "Final進出者のみ表示。決勝進出時、優勝時にポイント加算。";
	}

	// 集計対象外のマッチであるかを判定する。参加マッチ数計測のためのもの。
	// calcTotalScore はこれが true の場合のみ呼ばれる。
	public boolean isEnable(Round r) {
		return true;
	}

	// stat.participationCount / winCount / totalScore を設定する。
	// それぞれをどのような数値にするかは Maker 次第とする。
	// fixed round ごとに呼ばれる。対象外マッチ/ラウンドならなにもしないように実装する。
	// default は優勝数/参加マッチ数。final 進出、優勝でポイント加算。
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.participationCount = stat.matches.size();
		if (r.isFinal) {
			stat.totalScore += Core.PT_FINALS;
			if (p.qualified == Boolean.TRUE) {
				stat.winCount += 1;
				stat.totalScore += Core.PT_WIN;
			}
			return;
		}
	}

	public String getRanking(int sort, int minMatches) {
		List<PlayerStat> list;
		synchronized (Core.listLock) {
			list = new ArrayList<PlayerStat>(Core.stats.values());
		}
		if (list.size() == 0)
			return "";
		Comparator<PlayerStat> comp;
		switch (sort) {
		case 1:
			comp = new Core.StatComparatorWin();
			break;
		case 2:
			comp = new Core.StatComparatorRate();
			break;
		case 0:
		default:
			comp = new Core.StatComparatorScore();
			break;
		}
		Collections.sort(list, comp);
		return getRanking(list, minMatches, comp);
	}

	protected String getRanking(List<PlayerStat> list, int minMatches, Comparator<PlayerStat> comp) {
		StringBuilder buf = new StringBuilder();
		int internalNo = 0;
		int dispNo = 0;
		PlayerStat prev = null;
		for (PlayerStat stat : list) {
			if (stat.totalScore <= 0) // スコア０を表示するかどうかはモードにも依存するかもしれないがひとまず除外
				continue;
			if (stat.participationCount >= minMatches) {
				internalNo += 1;
				if (prev == null || comp.compare(stat, prev) != 0) {
					dispNo = internalNo;
				}
				buf.append(Core.pad(dispNo)).append(" ");
				prev = stat;

				buf.append("(").append(Core.pad(stat.winCount)).append("/").append(Core.pad(stat.participationCount))
						.append(") ").append(String.format("%6.2f", stat.getRate()))
						.append("% ").append(String.format("%3d", stat.totalScore)).append("pt");
				buf.append(" ").append(stat.name).append("\n");
			}
		}
		buf.append("total: ").append(internalNo).append("\n");
		return new String(buf);
	}
}

//レース１位時、決勝進出時、優勝時にポイント加算。
class FeedFirstRankingMaker extends RankingMaker {
	@Override
	public String toString() {
		return "Feed Point";
	}

	@Override
	public String getDesc() {
		return "race/hunt １位時、決勝進出時、優勝時にポイント加算。";
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.participationCount = stat.matches.size();
		if (r.isFinal) {
			stat.totalScore += Core.PT_FINALS;
			if (p.qualified == Boolean.TRUE) {
				stat.winCount += 1;
				stat.totalScore += Core.PT_WIN;
			}
			return;
		}
		// 順位に意味のある種目のみ
		RoundDef def = RoundDef.get(r.name);
		if (def.type == RoundType.RACE || def.type == RoundType.HUNT) {
			if (p.ranking == 1) // 1st
				stat.totalScore += Core.PT_1ST;
		}
	}
}

// スクアッドとしての決勝進出優勝でのランキング
class SquadsRankingMaker extends RankingMaker {
	@Override
	public String toString() {
		return "Squads";
	}

	@Override
	public String getDesc() {
		return "squads として優勝していれば優勝としてカウント。決勝進出時、優勝時にポイント加算。";
	}

	@Override
	public boolean isEnable(Round r) {
		// squadId のあるもののみ
		return r.byId.size() > 0 && r.byId.values().iterator().next().squadId != 0;
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.participationCount = stat.matches.size();
		if (r.isFinal) {
			stat.totalScore += Core.PT_FINALS;
			// メンバーの誰かに優勝者がいれば優勝とみなす。
			for (Player member : r.getSquad(p.squadId).members) {
				if (member.qualified == Boolean.TRUE) {
					stat.winCount += 1;
					stat.totalScore += Core.PT_WIN;
					return;
				}
			}
		}
	}
}

// FallBall Cup のみ
class FallBallRankingMaker extends RankingMaker {
	@Override
	public String toString() {
		return "FallBall";
	}

	@Override
	public String getDesc() {
		return "FallBall のみの勝率。";
	}

	@Override
	public boolean isEnable(Round r) {
		// fallball custom round のみ
		return r.name.equals("FallGuy_FallBall_5");
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.participationCount += 1; // 参加 round 数
		if (p.qualified == Boolean.TRUE) {
			stat.winCount += 1;
			stat.totalScore += 1;
		}
	}
}

// thieves のみの、ガーディアン、シーフ別戦績集計
class CandyRankingMaker extends RankingMaker {
	@Override
	public String toString() {
		return "Sweet Thieves";
	}

	@Override
	public String getDesc() {
		return "Sweet Thieves 専用集計です。total は切断も込の値。thief/guard はそれぞれのチーム別の戦績で最後までやった試合のデータです。";
	}

	@Override
	public boolean isEnable(Round r) {
		// thieves のみ
		return "FallGuy_Invisibeans".equals(r.name);
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.participationCount += 1;
		if (p.qualified == Boolean.TRUE)
			stat.winCount += 1;
		if (p.qualified == null)
			return; // 結果の出ていないものは個別集計から除外する
		boolean isGuard = false;
		boolean myResult = p.qualified == Boolean.TRUE;
		int sameResultPlayers = 0;
		stat.totalScore += myResult ? 1 : 0;

		for (Player o : r.byId.values())
			if (o.qualified != null && myResult == o.qualified)
				sameResultPlayers += 1;
		if (sameResultPlayers < r.byId.size() / 2)
			isGuard = true;

		if (isGuard) {
			int guardMatch = !stat.additional.containsKey("guardMatch") ? 0
					: Integer.parseInt(stat.additional.get("guardMatch"));
			int guardWin = !stat.additional.containsKey("guardWin") ? 0
					: Integer.parseInt(stat.additional.get("guardWin"));
			guardMatch += 1;
			guardWin += myResult ? 1 : 0;
			stat.additional.put("guardMatch", "" + guardMatch);
			stat.additional.put("guardWin", "" + guardWin);
		} else {
			int thiefMatch = !stat.additional.containsKey("thiefMatch") ? 0
					: Integer.parseInt(stat.additional.get("thiefMatch"));
			int thiefWin = !stat.additional.containsKey("thiefWin") ? 0
					: Integer.parseInt(stat.additional.get("thiefWin"));
			thiefMatch += 1;
			thiefWin += myResult ? 1 : 0;
			stat.additional.put("thiefMatch", "" + thiefMatch);
			stat.additional.put("thiefWin", "" + thiefWin);
		}
	}

	@Override
	protected String getRanking(List<PlayerStat> list, int minMatches, Comparator<PlayerStat> comp) {
		StringBuilder buf = new StringBuilder();
		int internalNo = 0;
		int dispNo = 0;
		PlayerStat prev = null;
		for (PlayerStat stat : list) {
			if (stat.participationCount >= minMatches) {
				internalNo += 1;
				if (prev == null || comp.compare(stat, prev) != 0) {
					dispNo = internalNo;
				}
				buf.append(Core.pad(dispNo)).append(" ").append(stat.name).append("\n");
				prev = stat;

				buf.append("  total: ").append(Core.pad(stat.winCount)).append("/")
						.append(Core.pad(stat.participationCount))
						.append(" (")
						.append(String.format("%6.2f", stat.getRate())).append("%)\n");
				buf.append("  thief: ").append(Core.pad(stat.getIntAdditional("thiefWin")))
						.append("/").append(Core.pad(stat.getIntAdditional("thiefMatch"))).append(" (")
						.append(String.format("%6.2f",
								Core.calRate(stat.getIntAdditional("thiefWin"),
										stat.getIntAdditional("thiefMatch"))))
						.append("%)\n");
				buf.append("  guard: ").append(Core.pad(stat.getIntAdditional("guardWin")))
						.append("/").append(Core.pad(stat.getIntAdditional("guardMatch"))).append(" (")
						.append(String.format("%6.2f",
								Core.calRate(stat.getIntAdditional("guardWin"),
										stat.getIntAdditional("guardMatch"))))
						.append("%)\n");
			}
		}
		return new String(buf);
	}
}

// match 単位で、存在した回数ランキング。自分にスナイプした回数順ということ。
class SnipeRankingMaker extends RankingMaker {
	@Override
	public String toString() {
		return "Snipes";
	}

	@Override
	public String getDesc() {
		return "マッチにいた回数順表示です。１位は必然的に自分になります。分子は優勝回数になっています。";
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.totalScore = stat.participationCount = stat.matches.size();
		if (r.isFinal && p.qualified == Boolean.TRUE) {
			stat.winCount += 1;
		}
	}
}

class Core {
	static boolean LANG_EN = true;
	static int PT_WIN = 10;
	static int PT_FINALS = 10;
	static int PT_1ST = 4;

	static Object listLock = new Object();

	// utilities
	public static double calRate(int win, int round) {
		if (round == 0)
			return 0;
		BigDecimal win_dec = new BigDecimal(win);
		BigDecimal round_dec = new BigDecimal(round);
		BigDecimal rate = win_dec.divide(round_dec, 4, BigDecimal.ROUND_HALF_UP);
		rate = rate.multiply(new BigDecimal("100"));
		rate = rate.setScale(2, RoundingMode.DOWN);
		return rate.doubleValue();
	}

	public static String pad(int v) {
		return String.format("%2d", v);
	}

	/*
	static String dump(byte[] bytes) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < bytes.length; i += 1) {
			b.append(Integer.toString(bytes[i] & 0xff, 16)).append(' ');
		}
		return b.toString();
	}
	*/

	//////////////////////////////////////////////////////////////
	public static long pingMS;
	public static String serverIp;
	public static String myName;
	public static RankingMaker rankingMaker = new RankingMaker();
	public static final List<Match> matches = new ArrayList<Match>();
	public static final List<Round> rounds = new ArrayList<Round>();
	public static final Map<String, PlayerStat> stats = new HashMap<String, PlayerStat>();
	public static Map<String, String> playerStyles = new HashMap<String, String>();

	public static void load() {
	}

	public static void save() {
	}

	public static void addMatch(Match m) {
		for (Match o : matches)
			if (m.id == o.id)
				return; // already added
		synchronized (listLock) {
			if (matches.size() > 1 && getCurrentMatch().rounds.size() == 0)
				matches.remove(matches.size() - 1);
			matches.add(m);
		}
	}

	public static void addRound(Round r) {
		for (Round o : rounds)
			if (r.id == o.id)
				return; // already added
		synchronized (listLock) {
			rounds.add(r);
			getCurrentMatch().rounds.add(r);
		}
	}

	public static PlayerStat getMyStat() {
		return stats.get(Core.myName);
	}

	public static Match getCurrentMatch() {
		if (matches.size() == 0)
			return null;
		return matches.get(matches.size() - 1);
	}

	public static Round getCurrentRound() {
		if (rounds.size() == 0)
			return null;
		return rounds.get(rounds.size() - 1);
	}

	public static void updateStats() {
		synchronized (listLock) {
			stats.clear();
			for (Round r : rounds) {
				if (!r.fixed)
					continue;
				if (!rankingMaker.isEnable(r))
					continue;
				// このラウンドの参加者の結果を反映
				for (Player p : r.byName.values()) {
					PlayerStat stat = stats.get(p.name);
					if (stat == null) {
						stat = new PlayerStat(p.name);
						stats.put(stat.name, stat);
					}
					stat.matches.add(r.match);
					rankingMaker.calcTotalScore(stat, p, r);
				}
			}
		}
	}

	static class StatComparatorScore implements Comparator<PlayerStat> {
		@Override
		public int compare(PlayerStat p1, PlayerStat p2) {
			int v = (int) Math.signum(p2.totalScore - p1.totalScore);
			if (v != 0)
				return v;
			// 第２ソートを rate とする。
			return (int) Math.signum(p2.getRate() - p1.getRate());
		}
	}

	static class StatComparatorWin implements Comparator<PlayerStat> {
		@Override
		public int compare(PlayerStat p1, PlayerStat p2) {
			int v = (int) Math.signum(p2.winCount - p1.winCount);
			if (v != 0)
				return v;
			// 第２ソートを rate とする。
			v = (int) Math.signum(p2.getRate() - p1.getRate());
			if (v != 0)
				return v;
			// 第３ソートを score とする。
			return (int) Math.signum(p2.totalScore - p1.totalScore);
		}
	}

	static class StatComparatorRate implements Comparator<PlayerStat> {
		@Override
		public int compare(PlayerStat p1, PlayerStat p2) {
			int v = (int) Math.signum(p2.getRate() - p1.getRate());
			if (v != 0)
				return v;
			// 第２ソートを score とする。
			return (int) Math.signum(p2.totalScore - p1.totalScore);
		}
	}

	static class PlayerComparator implements Comparator<Player> {
		@Override
		public int compare(Player p1, Player p2) {
			int v = (int) Math.signum(p2.finalScore - p1.finalScore);
			if (v != 0)
				return v;
			v = (int) Math.signum(p2.score - p1.score);
			if (v != 0)
				return v;
			return (int) Math.signum(p1.ranking - p2.ranking);
		}
	}

}

// wrap tailer
class FGReader extends TailerListenerAdapter {
	public interface Listener {
		void showUpdated();

		void roundStarted();

		void roundUpdated();

		void roundDone();
	}

	Tailer tailer;
	Thread thread;
	Listener listener;

	public FGReader(File log, Listener listener) {
		tailer = new Tailer(log, Charset.forName("UTF-8"), this, 400, false, false, 8192);
		this.listener = listener;
	}

	public void start() {
		thread = new Thread(tailer);
		thread.start();
	}

	public void stop() {
		tailer.stop();
		thread.interrupt();
	}

	//////////////////////////////////////////////////////////////////
	enum ReadState {
		SHOW_DETECTING, ROUND_DETECTING, MEMBER_DETECTING, RESULT_DETECTING
	}

	ReadState readState = ReadState.SHOW_DETECTING;
	int qualifiedCount = 0;
	int eliminatedCount = 0;
	boolean isFinal = false;
	long prevNetworkCheckedTime = System.currentTimeMillis();

	@Override
	public void handle(String line) {
		try {
			if (Core.myName == null && line.contains("[UserInfo] Player Name:")) {
				String[] sp = line.split("Player Name: ", 2);
				Core.myName = sp[1];
			}
			parseLine(line);
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}

	static Pattern patternServer = Pattern
			.compile("\\[StateConnectToGame\\] We're connected to the server! Host = ([^:]+)");

	static Pattern patternShowName = Pattern.compile("\\[HandleSuccessfulLogin\\] Selected show is ([^\\s]+)");
	//	static Pattern patternShow = Pattern
	//			.compile("\\[HandleSuccessfulLogin\\] Selected show is ([^\\s]+)");
	static Pattern patternMatchStart = Pattern.compile("\\[StateMatchmaking\\] Begin matchmaking ([^\\s]+)");
	static Pattern patternMatchStart2 = Pattern.compile("\\[StateMatchmaking\\] Begin party communications");
	static Pattern patternStartGame = Pattern.compile(
			"\\[StateGameLoading\\] Loading game level scene ([^\\s]+) - frame (\\d+)");

	static Pattern patternPlayerSpawn = Pattern.compile(
			"\\[CameraDirector\\] Adding Spectator target (.+) with Party ID: (\\d*)  Squad ID: (\\d+) and playerID: (\\d+)");
	static Pattern patternPlayerSpawn2 = Pattern.compile(
			"\\[StateGameLoading\\] OnPlayerSpawned - name=FallGuy \\[(\\d+)\\] .+ \\([^)]+\\) ID=(\\d+) was spawned");

	static Pattern patternScoreUpdated = Pattern.compile("Player (\\d+) score = (\\d+)");
	static Pattern patternPlayerResult = Pattern.compile(
			"ClientGameManager::HandleServerPlayerProgress PlayerId=(\\d+) is succeeded=([^\\s]+)");

	static Pattern patternPlayerResult2 = Pattern.compile(
			"-playerId:(\\d+) points:(\\d+) isfinal:([^\\s]+) name:");

	static DateFormat f = new SimpleDateFormat("HH:mm:ss.SSS");

	static Date getTime(String line) {
		try {
			Date date = f.parse(line.substring(0, 12));
			return date;
		} catch (ParseException e) {
			e.printStackTrace();
		}
		return null;
	}

	private void parseLine(String line) {
		Matcher m = patternServer.matcher(line);
		if (m.find()) {
			String showName = "_";
			long id = (int) (Math.random() * 65536); // FIXME
			Core.addMatch(new Match(showName, id));
			System.out.println("DETECT SHOW STARTING");
			readState = ReadState.ROUND_DETECTING;
			listener.showUpdated();

			String ip = m.group(1);
			if (!ip.equals(Core.serverIp)) {
				System.out.println("new server detected: " + ip);
				long now = System.currentTimeMillis();
				if (Core.pingMS == 0 || prevNetworkCheckedTime + 60 * 1000 < now) {
					Core.serverIp = ip;
					prevNetworkCheckedTime = now;
					// ping check
					try {
						InetAddress address = InetAddress.getByName(ip);
						boolean res = address.isReachable(3000);
						Core.pingMS = System.currentTimeMillis() - now;
						System.out.println("PING " + res + " " + Core.pingMS);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
		switch (readState) {
		case SHOW_DETECTING: // start show or round detection
		case ROUND_DETECTING: // start round detection
			m = patternShowName.matcher(line);
			if (m.find()) {
				String showName = m.group(1);
				Core.getCurrentMatch().name = showName;
				listener.showUpdated();
				break;
			}
			if (line.contains("isFinalRound=")) {
				isFinal = line.contains("isFinalRound=True");
				break;
			}
			m = patternStartGame.matcher(line);
			if (m.find()) {
				String roundName = m.group(1);
				long frame = Long.parseUnsignedLong(m.group(2)); // FIXME: round id のほうが適切
				Core.addRound(new Round(roundName, frame, isFinal, Core.getCurrentMatch()));
				System.out.println("DETECT STARTING " + roundName + " frame=" + frame);
				readState = ReadState.MEMBER_DETECTING;
			}
			break;
		case MEMBER_DETECTING: // join detection
			m = patternPlayerSpawn.matcher(line);
			if (m.find()) {
				String name = m.group(1);
				int partyId = m.group(2).length() == 0 ? 0 : Integer.parseUnsignedInt(m.group(2)); // 空文字列のことあり
				int squadId = Integer.parseUnsignedInt(m.group(3));
				int playerId = Integer.parseUnsignedInt(m.group(4));
				String playerName = name.substring(4, name.length() - 6);
				Core.getCurrentRound().add(playerName, playerId, squadId, partyId);
				System.out.println(Core.getCurrentRound().byId.size() + " Player " + playerName + " (id=" + playerId
						+ " squadId=" + squadId + ") spwaned.");
				break;
			}
			m = patternPlayerSpawn2.matcher(line);
			if (m.find()) {
				int playerObjectId = Integer.parseUnsignedInt(m.group(1));
				int playerId = Integer.parseUnsignedInt(m.group(2));
				Player p = Core.getCurrentRound().byId.get(playerId);
				if (p != null)
					p.objectId = playerObjectId;
				// System.out.println("playerId=" + playerId + " objectId=" + playerObjectId);
				break;
			}
			if (line.contains("[StateGameLoading] Starting the game.")) {
				System.out.println("DETECT STARTING GAME");
				Core.getCurrentRound().start = getTime(line);
				listener.roundStarted();
				qualifiedCount = eliminatedCount = 0; // reset
				readState = ReadState.RESULT_DETECTING;
				break;
			}
			if (line.contains("[StateMainMenu] Creating or joining lobby")
					|| line.contains("[StateMatchmaking] Begin matchmaking")) {
				System.out.println("DETECT BACK TO LOBBY");
				Core.rounds.remove(Core.rounds.size() - 1); // delete current round
				readState = ReadState.SHOW_DETECTING;
				break;
			}
			break;
		case RESULT_DETECTING: // result detection
			// score update duaring round
			m = patternScoreUpdated.matcher(line);
			if (m.find()) {
				int playerObjectId = Integer.parseUnsignedInt(m.group(1));
				int score = Integer.parseUnsignedInt(m.group(2));
				Player player = Core.getCurrentRound().getByObjectId(playerObjectId);
				if (player != null) {
					if (player.score != score) {
						System.out.println(player + " score " + player.score + " -> " + score);
						player.score = score;
						listener.roundUpdated();
					}
				}
				break;
			}
			// qualified / eliminated
			m = patternPlayerResult.matcher(line);
			if (m.find()) {
				int playerId = Integer.parseUnsignedInt(m.group(1));
				boolean succeeded = "True".equals(m.group(2));
				Round r = Core.getCurrentRound();
				Player player = r.byId.get(playerId);
				if (!succeeded)
					System.out.print("Eliminated for " + playerId + " ");
				if (player != null) {
					player.qualified = succeeded;
					if (succeeded) {
						if (player.score == 0) {
							// スコア出力がない場合の仮スコア付
							switch (RoundDef.get(r.name).type) {
							case RACE:
							case HUNT:
								player.score = r.byId.size() - qualifiedCount;
								break;
							case LOGIC:
							case SURVIVAL:
							case TEAM:
								player.score = 1;
								break;
							}
						}
						qualifiedCount += 1;
						player.ranking = qualifiedCount;
						System.out.println("Qualified " + player + " rank=" + player.ranking + " " + player.score);
					} else {
						player.ranking = r.byId.size() - eliminatedCount;
						eliminatedCount += 1;
						System.out.println(player);
					}
					listener.roundUpdated();
				}
				break;
			}
			// score log
			// round over より後に出力されている。
			m = patternPlayerResult2.matcher(line);
			if (m.find()) {
				int playerId = Integer.parseUnsignedInt(m.group(1));
				int finalScore = Integer.parseUnsignedInt(m.group(2));
				boolean isFinal = "True".equals(m.group(3));
				Player player = Core.getCurrentRound().byId.get(playerId);
				System.out.println(
						"Result for " + playerId + " score=" + finalScore + " isFinal=" + isFinal + " " + player);
				if (player != null) {
					player.finalScore = finalScore;
				}
				break;
			}
			// round end
			//if (text.contains("[ClientGameManager] Server notifying that the round is over.")
			if (line.contains(
					"[GameStateMachine] Replacing FGClient.StateGameInProgress with FGClient.StateQualificationScreen")
					|| line.contains(
							"[GameStateMachine] Replacing FGClient.StateGameInProgress with FGClient.StateVictoryScreen")) {
				System.out.println("DETECT END GAME");
				Core.getCurrentRound().fixed = true;
				Core.getCurrentRound().end = getTime(line);
				Core.updateStats();
				listener.roundDone();
				readState = ReadState.ROUND_DETECTING;
				break;
			}
			if (line.contains("== [CompletedEpisodeDto] ==")) {
				// 獲得 kudos 他はこの後に続く、決勝完了前に吐くこともあるのでステージ完了ではない。

				break;
			}
			if (line.contains(
					"[GameStateMachine] Replacing FGClient.StatePrivateLobby with FGClient.StateMainMenu")
					|| line.contains("[StateMainMenu] Creating or joining lobby")
					|| line.contains("[StateMainMenu] Loading scene MainMenu")
					|| line.contains("[StateMatchmaking] Begin matchmaking")
					|| line.contains("Changing local player state to: SpectatingEliminated")
					|| line.contains("[GlobalGameStateClient] SwitchToDisconnectingState")) {
				readState = ReadState.SHOW_DETECTING;
				break;
			}
			break;
		}
	}
}

// UI
public class FallGuysRecord extends JFrame implements FGReader.Listener {
	static int FONT_SIZE_RANK;
	static int FONT_SIZE_DETAIL;

	static FallGuysRecord frame;
	static FGReader reader;
	static String monospacedFontFamily = "MS Gothic";
	static String fontFamily = "Meiryo UI";

	public static void main(String[] args) throws Exception {
		//	UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
		//	UIManager.setLookAndFeel("com.sun.java.swing.plaf.motif.MotifLookAndFeel");
		UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");

		Properties prop = new Properties();
		try (BufferedReader br = new BufferedReader(new FileReader("settings.ini"))) {
			prop.load(br);
		} catch (FileNotFoundException e) {
		}
		// default values
		String v = prop.getProperty("LANGUAGE");
		Core.LANG_EN = v == null || "en".equals(v);
		v = prop.getProperty("POINT_1ST");
		Core.PT_1ST = v == null ? 4 : Integer.parseInt(v, 10);
		v = prop.getProperty("POINT_FINALS");
		Core.PT_FINALS = v == null ? 10 : Integer.parseInt(v, 10);
		v = prop.getProperty("POINT_WIN");
		Core.PT_WIN = v == null ? 10 : Integer.parseInt(v, 10);

		v = prop.getProperty("FONT_SIZE_RANK");
		FONT_SIZE_RANK = v == null ? 16 : Integer.parseInt(v, 10);
		v = prop.getProperty("FONT_SIZE_DETAIL");
		FONT_SIZE_DETAIL = v == null ? 16 : Integer.parseInt(v, 10);

		Rectangle winRect = new Rectangle(10, 10, 1280, 628);
		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream("state.dat"))) {
			winRect = (Rectangle) in.readObject();
			Core.playerStyles = (Map<String, String>) in.readObject();
		} catch (IOException ex) {
		}

		Core.load();

		frame = new FallGuysRecord();
		frame.setResizable(true);
		frame.setBounds(winRect.x, winRect.y, winRect.width, winRect.height);
		frame.setTitle("Fall Guys Record");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}

	JLabel myStatLabel;
	JLabel pingLabel;
	JList<String> matchSel;
	JList<String> roundsSel;
	JTextPane roundDetailArea;
	JTextPane rankingArea;
	JComboBox<String> rankingSortSel;
	JComboBox<Integer> rankingFilterSel;
	JComboBox<String> playerSel;
	JComboBox<String> playerMarkingSel;
	JLabel rankingDescLabel;
	boolean ignoreSelEvent;

	static final int LINE1_Y = 10;
	static final int LINE2_Y = 40;
	static final int LINE4_Y = 498;
	static final int LINE5_Y = 530;
	static final int LINE6_Y = 560;
	static final int COL1_X = 10;
	static final int COL2_X = 400;
	static final int COL3_X = 530;
	static final int COL4_X = 690;

	FallGuysRecord() {
		SpringLayout l = new SpringLayout();
		Container p = getContentPane();
		p.setLayout(l);

		JLabel label = new JLabel("【総合ランキング】");
		label.setFont(new Font(fontFamily, Font.BOLD, 14));
		l.putConstraint(SpringLayout.WEST, label, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(200, 20);
		p.add(label);

		rankingSortSel = new JComboBox<String>();
		rankingSortSel.setFont(new Font(fontFamily, Font.BOLD, 12));
		l.putConstraint(SpringLayout.WEST, rankingSortSel, 10, SpringLayout.EAST, label);
		l.putConstraint(SpringLayout.NORTH, rankingSortSel, LINE1_Y, SpringLayout.NORTH, p);
		rankingSortSel.setSize(95, 20);
		rankingSortSel.addItem("スコア順");
		rankingSortSel.addItem("勝利数順");
		rankingSortSel.addItem("勝率順");
		rankingSortSel.addItemListener(ev -> {
			displayRanking();
		});
		p.add(rankingSortSel);

		rankingFilterSel = new JComboBox<Integer>();
		rankingFilterSel.setFont(new Font(fontFamily, Font.BOLD, 12));
		l.putConstraint(SpringLayout.WEST, rankingFilterSel, 4, SpringLayout.EAST, rankingSortSel);
		l.putConstraint(SpringLayout.NORTH, rankingFilterSel, LINE1_Y, SpringLayout.NORTH, p);
		rankingFilterSel.setSize(44, 20);
		rankingFilterSel.addItem(1);
		rankingFilterSel.addItem(3);
		rankingFilterSel.addItem(10);
		rankingFilterSel.addItem(20);
		rankingFilterSel.addItem(25);
		rankingFilterSel.addItem(30);
		rankingFilterSel.addItemListener(ev -> {
			displayRanking();
		});
		p.add(rankingFilterSel);
		label = new JLabel("試合以上のみを表示");
		label.setFont(new Font(fontFamily, Font.PLAIN, 12));
		l.putConstraint(SpringLayout.WEST, label, 4, SpringLayout.EAST, rankingFilterSel);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(120, 20);
		p.add(label);

		label = new JLabel("【マッチ一覧】");
		label.setFont(new Font(fontFamily, Font.BOLD, 14));
		l.putConstraint(SpringLayout.WEST, label, COL2_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);
		label = new JLabel("【ラウンド一覧】");
		label.setFont(new Font(fontFamily, Font.BOLD, 14));
		l.putConstraint(SpringLayout.WEST, label, COL3_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);
		label = new JLabel("【ラウンド詳細】");
		label.setFont(new Font(fontFamily, Font.BOLD, 14));
		l.putConstraint(SpringLayout.WEST, label, COL4_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);

		// under
		myStatLabel = new JLabel("");
		myStatLabel.setFont(new Font(fontFamily, Font.BOLD, 20));
		l.putConstraint(SpringLayout.WEST, myStatLabel, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.SOUTH, myStatLabel, -10, SpringLayout.SOUTH, p);
		myStatLabel.setPreferredSize(new Dimension(300, 20));
		p.add(myStatLabel);

		pingLabel = new JLabel("");
		pingLabel.setFont(new Font(fontFamily, Font.PLAIN, 16));
		l.putConstraint(SpringLayout.WEST, pingLabel, COL4_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.SOUTH, pingLabel, -10, SpringLayout.SOUTH, p);
		pingLabel.setPreferredSize(new Dimension(300, 20));
		p.add(pingLabel);

		JScrollPane scroller;
		JComboBox<RankingMaker> rankingMakerSel = new JComboBox<RankingMaker>();
		rankingMakerSel.setFont(new Font(fontFamily, Font.BOLD, 12));
		l.putConstraint(SpringLayout.WEST, rankingMakerSel, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.SOUTH, rankingMakerSel, -10, SpringLayout.NORTH, myStatLabel);
		rankingMakerSel.setPreferredSize(new Dimension(150, 20));
		rankingMakerSel.setBounds(COL1_X, LINE5_Y, 150, 25);
		p.add(rankingMakerSel);
		rankingMakerSel.addItem(new RankingMaker());
		rankingMakerSel.addItem(new FeedFirstRankingMaker());
		rankingMakerSel.addItem(new SquadsRankingMaker());
		rankingMakerSel.addItem(new FallBallRankingMaker());
		rankingMakerSel.addItem(new CandyRankingMaker());
		rankingMakerSel.addItem(new SnipeRankingMaker());
		rankingMakerSel.addItemListener(ev -> {
			Core.rankingMaker = (RankingMaker) rankingMakerSel.getSelectedItem();
			rankingDescLabel.setText(Core.rankingMaker.getDesc());
			Core.updateStats();
			displayRanking();
		});
		rankingDescLabel = new JLabel(Core.rankingMaker.getDesc());
		rankingDescLabel.setFont(new Font(fontFamily, Font.PLAIN, 14));
		l.putConstraint(SpringLayout.WEST, rankingDescLabel, 10, SpringLayout.EAST, rankingMakerSel);
		l.putConstraint(SpringLayout.SOUTH, rankingDescLabel, -10, SpringLayout.NORTH, myStatLabel);
		rankingDescLabel.setPreferredSize(new Dimension(800, 20));
		p.add(rankingDescLabel);

		rankingArea = new NoWrapJTextPane();
		rankingArea.setFont(new Font(monospacedFontFamily, Font.PLAIN, FONT_SIZE_RANK));
		p.add(scroller = new JScrollPane(rankingArea));
		l.putConstraint(SpringLayout.WEST, scroller, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, LINE2_Y, SpringLayout.NORTH, p);
		l.putConstraint(SpringLayout.SOUTH, scroller, -10, SpringLayout.NORTH, rankingMakerSel);
		scroller.setPreferredSize(new Dimension(380, 0));

		matchSel = new JList<String>(new DefaultListModel<String>());
		matchSel.setFont(new Font(fontFamily, Font.PLAIN, 16));
		p.add(scroller = new JScrollPane(matchSel));
		l.putConstraint(SpringLayout.WEST, scroller, COL2_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, LINE2_Y, SpringLayout.NORTH, p);
		l.putConstraint(SpringLayout.SOUTH, scroller, -40, SpringLayout.NORTH, rankingMakerSel);
		scroller.setPreferredSize(new Dimension(120, 0));
		matchSel.addListSelectionListener((ev) -> {
			if (ev.getValueIsAdjusting()) {
				// The user is still manipulating the selection.
				return;
			}
			int index = matchSel.getSelectedIndex();
			if (index < 0 || index > Core.rounds.size())
				return;
			matchSelected(index == 0 ? null : Core.matches.get(index - 1));
		});

		roundsSel = new JList<String>(new DefaultListModel<String>());
		roundsSel.setFont(new Font(fontFamily, Font.PLAIN, 16));
		p.add(scroller = new JScrollPane(roundsSel));
		l.putConstraint(SpringLayout.WEST, scroller, COL3_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, LINE2_Y, SpringLayout.NORTH, p);
		l.putConstraint(SpringLayout.SOUTH, scroller, -40, SpringLayout.NORTH, rankingMakerSel);
		scroller.setPreferredSize(new Dimension(150, 0));
		roundsSel.addListSelectionListener((ev) -> {
			if (ev.getValueIsAdjusting()) {
				// The user is still manipulating the selection.
				return;
			}
			roundSelected(getSelectedRound());
		});

		// styles
		StyledDocument doc = new DefaultStyledDocument();
		Style def = doc.getStyle(StyleContext.DEFAULT_STYLE);
		Style s = doc.addStyle("bold", def);
		StyleConstants.setBold(s, true);
		s = doc.addStyle("green", def);
		StyleConstants.setForeground(s, new Color(0x00cc00));
		//StyleConstants.setBold(s, true);
		s = doc.addStyle("blue", def);
		StyleConstants.setForeground(s, Color.BLUE);
		//StyleConstants.setBold(s, true);
		s = doc.addStyle("cyan", def);
		StyleConstants.setForeground(s, new Color(0x00cccc));
		s = doc.addStyle("magenta", def);
		StyleConstants.setForeground(s, new Color(0xcc00cc));
		//StyleConstants.setBold(s, true);
		s = doc.addStyle("yellow", def);
		StyleConstants.setForeground(s, new Color(0xcccc00));
		s = doc.addStyle("red", def);
		StyleConstants.setForeground(s, Color.RED);
		//StyleConstants.setBold(s, true);

		roundDetailArea = new NoWrapJTextPane(doc);
		roundDetailArea.setFont(new Font(monospacedFontFamily, Font.PLAIN, FONT_SIZE_DETAIL));
		p.add(scroller = new JScrollPane(roundDetailArea));
		l.putConstraint(SpringLayout.WEST, scroller, COL4_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.EAST, scroller, -10, SpringLayout.EAST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, LINE2_Y, SpringLayout.NORTH, p);
		l.putConstraint(SpringLayout.SOUTH, scroller, -40, SpringLayout.NORTH, rankingMakerSel);

		playerSel = new JComboBox<String>();
		playerSel.setFont(new Font(fontFamily, Font.BOLD, 12));
		l.putConstraint(SpringLayout.WEST, playerSel, COL2_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.SOUTH, playerSel, -10, SpringLayout.NORTH, rankingMakerSel);
		playerSel.setPreferredSize(new Dimension(150, 20));
		p.add(playerSel);
		playerSel.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				String player = (String) playerSel.getSelectedItem();
				String before = (String) playerMarkingSel.getSelectedItem();
				String after = Core.playerStyles.get(player);
				if ((before == null && after == null) || (before != null && before.equals(after)))
					return;
				ignoreSelEvent = true;
				playerMarkingSel.setSelectedItem(after);
			}
		});

		playerMarkingSel = new JComboBox<String>();
		playerMarkingSel.setFont(new Font(fontFamily, Font.BOLD, 12));
		l.putConstraint(SpringLayout.WEST, playerMarkingSel, 10, SpringLayout.EAST, playerSel);
		l.putConstraint(SpringLayout.NORTH, playerMarkingSel, 0, SpringLayout.NORTH, playerSel);
		playerMarkingSel.setPreferredSize(new Dimension(80, 20));
		p.add(playerMarkingSel);
		playerMarkingSel.addItem("");
		playerMarkingSel.addItem("bold");
		playerMarkingSel.addItem("green");
		playerMarkingSel.addItem("blue");
		playerMarkingSel.addItem("cyan");
		playerMarkingSel.addItem("magenta");
		playerMarkingSel.addItem("yellow");
		playerMarkingSel.addItem("red");
		playerMarkingSel.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent e) {
				if (ignoreSelEvent) {
					ignoreSelEvent = false;
					return;
				}
				String player = (String) playerSel.getSelectedItem();
				String style = (String) playerMarkingSel.getSelectedItem();
				if (style == null || style.length() == 0)
					Core.playerStyles.remove(player);
				else
					Core.playerStyles.put(player, style);
				refreshRoundDetail(getSelectedRound());
				displayRanking();
			}
		});

		JButton removeMemberFromRoundButton = new JButton("ラウンドから参加者を外す");
		removeMemberFromRoundButton.setFont(new Font(fontFamily, Font.BOLD, 14));
		l.putConstraint(SpringLayout.WEST, removeMemberFromRoundButton, 10, SpringLayout.EAST, playerMarkingSel);
		l.putConstraint(SpringLayout.NORTH, removeMemberFromRoundButton, 0, SpringLayout.NORTH, playerSel);
		removeMemberFromRoundButton.setPreferredSize(new Dimension(180, 20));
		removeMemberFromRoundButton.addActionListener(ev -> removePlayerOnCurrentRound());
		p.add(removeMemberFromRoundButton);

		JButton removeMemberFromMatchButton = new JButton("マッチから参加者を外す");
		removeMemberFromMatchButton.setFont(new Font(fontFamily, Font.BOLD, 14));
		l.putConstraint(SpringLayout.WEST, removeMemberFromMatchButton, 10, SpringLayout.EAST,
				removeMemberFromRoundButton);
		l.putConstraint(SpringLayout.NORTH, removeMemberFromMatchButton, 0, SpringLayout.NORTH, playerSel);
		removeMemberFromMatchButton.setPreferredSize(new Dimension(180, 20));
		removeMemberFromMatchButton.addActionListener(ev -> removePlayerOnCurrentMatch());
		p.add(removeMemberFromMatchButton);

		this.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				reader.stop();
				try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream("state.dat"))) {
					out.writeObject(frame.getBounds());
					out.writeObject(Core.playerStyles);
				} catch (IOException ex) {
					ex.printStackTrace();
				}
				Core.save();
			}
		});

		// start log read
		reader = new FGReader(
				new File(FileUtils.getUserDirectory(), "AppData/LocalLow/Mediatonic/FallGuys_client/Player.log"), this);
		reader.start();
	}

	void updateMatches() {
		int prevSelectedIndex = matchSel.getSelectedIndex();
		pingLabel.setText("PING: " + Core.pingMS + "ms(" + Core.serverIp + ")");
		DefaultListModel<String> model = (DefaultListModel<String>) matchSel.getModel();
		model.clear();
		model.addElement("ALL");
		synchronized (Core.listLock) {
			for (Match m : Core.matches) {
				model.addElement(m.name);
			}
			matchSel.setSelectedIndex(prevSelectedIndex <= 0 ? 0 : model.getSize() - 1);
			matchSel.ensureIndexIsVisible(matchSel.getSelectedIndex());
		}
	}

	void updateRounds() {
		DefaultListModel<String> model = (DefaultListModel<String>) roundsSel.getModel();
		model.clear();
		synchronized (Core.listLock) {
			Match m = getSelectedMatch();
			for (Round r : m == null ? Core.rounds : m.rounds) {
				RoundDef def = RoundDef.get(r.name);
				model.addElement(Core.LANG_EN ? def.dispName : def.dispNameJa);
			}
			roundsSel.setSelectedIndex(model.size() - 1);
			roundsSel.ensureIndexIsVisible(roundsSel.getSelectedIndex());
			displayRanking();
		}
	}

	void matchSelected(Match m) {
		DefaultListModel<String> model = (DefaultListModel<String>) roundsSel.getModel();
		model.clear();
		synchronized (Core.listLock) {
			for (Round r : m == null ? Core.rounds : m.rounds) {
				RoundDef def = RoundDef.get(r.name);
				model.addElement(Core.LANG_EN ? def.dispName : def.dispNameJa);
			}
		}
		roundsSel.setSelectedIndex(model.size() - 1);
		roundsSel.ensureIndexIsVisible(roundsSel.getSelectedIndex());
	}

	private void appendToRanking(String str, String style) {
		style = style == null ? StyleContext.DEFAULT_STYLE : style;
		StyledDocument doc = rankingArea.getStyledDocument();
		try {
			doc.insertString(doc.getLength(), str + "\n", doc.getStyle(style));
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
	}

	private void appendToRoundDetail(String str, String style) {
		style = style == null ? StyleContext.DEFAULT_STYLE : style;
		StyledDocument doc = roundDetailArea.getStyledDocument();
		try {
			doc.insertString(doc.getLength(), str + "\n", doc.getStyle(style));
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
	}

	void roundSelected(Round r) {
		updatePlayerSel(r);
		refreshRoundDetail(r);
	}

	void refreshRoundDetail(Round r) {
		roundDetailArea.setText("");
		if (r == null) {
			return;
		}
		if (r.isFinal) {
			appendToRoundDetail("********** FINAL **********", "bold");
		}
		synchronized (Core.listLock) {
			List<Squad> squads = r.bySquadRank();
			if (squads != null) {
				int internalNo = 0;
				int dispNo = 0;
				Squad prev = null;
				for (Squad s : squads) {
					internalNo += 1;
					if (prev == null || s.getScore() != prev.getScore()) {
						dispNo = internalNo;
					}
					prev = s;

					appendToRoundDetail(
							Core.pad(dispNo) + " " + Core.pad(s.getScore()) + "pt ______________ squad=" + s.squadId,
							null);

					for (Player p : s.members)
						appendToRoundDetail((Core.myName.equals(p.name) ? "★" : p.partyId != 0 ? "p " : "　")
								+ " " + (p.qualified == null ? "　" : p.qualified ? "○" : "✕") + Core.pad(p.score)
								+ "pt("
								+ Core.pad(p.finalScore) + ")" + " " + p.name, Core.playerStyles.get(p.name));
				}
				appendToRoundDetail("********** solo rank **********", null);
			}
			appendToRoundDetail("rank " + (squads != null ? "sq " : "") + "  score  pt   name", null);
			for (Player p : r.byRank()) {
				StringBuilder buf = new StringBuilder();
				buf.append(p.qualified == null ? "　" : p.qualified ? "○" : "✕");
				buf.append(Core.pad(p.ranking)).append(" ");
				if (squads != null)
					buf.append(Core.pad(p.squadId)).append(" ");
				buf.append(Core.pad(p.score)).append("pt(").append(Core.pad(p.finalScore)).append(")")
						.append(" ").append(p.partyId != 0 ? Core.pad(p.partyId) + " " : "   ");
				buf.append(Core.myName.equals(p.name) ? "★" : "　").append(p.name.startsWith("_") ? "B" : "")
						.append(p.name);
				appendToRoundDetail(new String(buf), Core.playerStyles.get(p.name));
			}
		}
		roundDetailArea.setCaretPosition(0);
	}

	@Override
	public void showUpdated() {
		SwingUtilities.invokeLater(() -> {
			updateMatches();
		});
	}

	@Override
	public void roundStarted() {
		SwingUtilities.invokeLater(() -> {
			updateRounds();
		});
	}

	@Override
	public void roundUpdated() {
		if (Core.getCurrentRound() == getSelectedRound())
			SwingUtilities.invokeLater(() -> {
				refreshRoundDetail(getSelectedRound());
			});
	}

	@Override
	public void roundDone() {
		SwingUtilities.invokeLater(() -> {
			updateRounds();
		});
	}

	Match getSelectedMatch() {
		int matchIndex = matchSel.getSelectedIndex();
		if (matchIndex < 1)
			return null;
		synchronized (Core.listLock) {
			return Core.matches.get(matchIndex - 1);
		}
	}

	Round getSelectedRound() {
		int roundIndex = roundsSel.getSelectedIndex();
		if (roundIndex < 0)
			return null;
		synchronized (Core.listLock) {
			Match m = getSelectedMatch();
			if (m == null)
				return Core.rounds.get(roundIndex);
			return m.rounds.get(roundIndex);
		}
	}

	public void updatePlayerSel(Round r) {
		playerSel.removeAllItems();
		if (r == null)
			return;
		synchronized (Core.listLock) {
			for (Player player : r.byName.values()) {
				FallGuysRecord.frame.playerSel.addItem(player.name);
			}
		}
		//playerSel.setSelectedItem(Core.myName);
	}

	private void removePlayerOnCurrentRound() {
		String name_selected = (String) playerSel.getSelectedItem();
		Round r = getSelectedRound();
		r.remove(name_selected);
		Core.updateStats();
		roundSelected(r);
		displayRanking();
	}

	private void removePlayerOnCurrentMatch() {
		String name_selected = (String) playerSel.getSelectedItem();
		Match m = getSelectedMatch();
		for (Round r : m == null ? Core.rounds : m.rounds)
			r.remove(name_selected);
		Core.updateStats();
		roundSelected(getSelectedRound());
		displayRanking();
	}

	void displayRanking() {
		rankingArea.setText(Core.rankingMaker.getRanking(
				rankingSortSel.getSelectedIndex(),
				(Integer) rankingFilterSel.getSelectedItem()));
		rankingArea.setCaretPosition(0);
		PlayerStat own = Core.getMyStat();
		if (own != null)
			myStatLabel
					.setText("自分の戦績: " + own.winCount + " / " + own.participationCount + " (" + own.getRate() + "%)");
	}
}

class NoWrapJTextPane extends JTextPane {
	public NoWrapJTextPane() {
		super();
	}

	public NoWrapJTextPane(StyledDocument doc) {
		super(doc);
	}

	@Override
	public boolean getScrollableTracksViewportWidth() {
		// Only track viewport width when the viewport is wider than the preferred width
		return getUI().getPreferredSize(this).width <= getParent().getSize().width;
	};

	@Override
	public Dimension getPreferredSize() {
		// Avoid substituting the minimum width for the preferred width when the viewport is too narrow
		return getUI().getPreferredSize(this);
	};
}
