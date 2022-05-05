import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;

//集計後のプレイヤーごと
class PlayerStat {
	String name; // for user identication
	int totalScore;
	int roundCount;
	int winCount;
	Map<String, String> additional = new HashMap<String, String>(); // 独自の統計を使う場合用領域

	public PlayerStat(String name) {
		this.name = name;
	}

	public double getRate() {
		return Core.calRate(winCount, roundCount);
	}

	/*
	public double getRateSameteam() {
		return Core.calRate(sameteam, round);
	}

	public double getRateSameteamWin() {
		return Core.calRate(sameteamWin, sameteam);
	}
	*/

	public int getInt(String key) {
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
	int squadId;
	int partyId;
	int ranking; // rank of current round

	Boolean win;
	int score; // 独自の基準でスコア付したい場合用

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

class Round {
	boolean fixed; // ステージ完了まで読み込み済み
	boolean isFinal;
	String name;
	long id; // 過去に読み込んだステージであるかの判定用。厳密ではないが frame 数なら衝突確率は低い。
	Date start;
	Date end;
	Map<String, Player> byName = new HashMap<String, Player>();
	Map<Integer, Player> byId = new HashMap<Integer, Player>();

	public Round(String name, long id, boolean isFinal) {
		this.name = name;
		this.id = id;
		this.isFinal = isFinal;
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

	public int getPlayerCount() {
		return byId.size();
	}

	public ArrayList<Player> byRank() {
		ArrayList<Player> list = new ArrayList<Player>(byName.values());
		Collections.sort(list, new Comparator<Player>() {
			@Override
			public int compare(Player p1, Player p2) {
				return (int) Math.signum(p2.score - p1.score);
			}
		});
		return list;
	}
}

// 一つのショー
class Match {
	boolean fixed; // 完了まで読み込み済み
	String name;
	long id; // 過去に読み込んだステージであるかの判定用。厳密ではないが frame 数なら衝突確率は低い。
	Date start;
	Date end;
	List<Round> rounds = new ArrayList<Round>();

	public Match(String name, long id) {
		this.name = name;
		this.id = id;
	}
}

class RoundDef {
	public static final int RACE = 0;
	public static final int HUNT = 1;
	public static final int SURVIVAL = 2;
	public static final int LOGIC = 3;
	public static final int TEAM = 4;

	public final String dispName;
	public final String dispNameJa;
	public final int type;

	public RoundDef(String name, String nameJa, int type) {
		dispName = name;
		dispNameJa = nameJa;
		this.type = type;
	}

	static Map<String, RoundDef> roundNames = new HashMap<String, RoundDef>();
	static {
		roundNames.put("FallGuy_Airtime", new RoundDef("Airtime", "エアタイム", HUNT));
		roundNames.put("FallGuy_BiggestFan", new RoundDef("Big Fans", "ビッグファン", RACE));
		roundNames.put("FallGuy_KingOfTheHill2", new RoundDef("Bubble Trouble", "バブルトラブル", HUNT));
		roundNames.put("FallGuy_1v1_ButtonBasher", new RoundDef("Button Bashers", "ボタンバッシャーズ", HUNT));
		roundNames.put("FallGuy_DoorDash", new RoundDef("Door Dash", "ドアダッシュ", RACE));
		roundNames.put("FallGuy_Gauntlet_02_01", new RoundDef("Dizzy Heights", "スピンレース", RACE));
		roundNames.put("FallGuy_IceClimb_01", new RoundDef("Freezy Peak", "スノーマウンテン", RACE));
		roundNames.put("FallGuy_DodgeFall", new RoundDef("Fruit Chute", "フルーツパニック", RACE));
		roundNames.put("FallGuy_SeeSaw360", new RoundDef("Full Tilt", "フルティルト", RACE));
		roundNames.put("FallGuy_ChompChomp_01", new RoundDef("Gate Crash", "ゲートクラッシュ", RACE));
		roundNames.put("FallGuy_Gauntlet_01", new RoundDef("Hit Parade", "ヒットパレード", RACE));
		roundNames.put("FallGuy_Hoops_Blockade", new RoundDef("Hoopsie Legends", "フープループレジェンド", HUNT));
		roundNames.put("FallGuy_Gauntlet_04", new RoundDef("Knight Fever", "ナイトフィーバー", RACE));
		roundNames.put("FallGuy_FollowTheLeader", new RoundDef("Leading Light", "動くスポットライト", HUNT));
		roundNames.put("FallGuy_DrumTop", new RoundDef("Lily Leapers", "リリー・リーパー", RACE));
		roundNames.put("FallGuy_Gauntlet_08", new RoundDef("Party Promenade", "パーティプロムナード", RACE));
		roundNames.put("FallGuy_Penguin_Solos", new RoundDef("Pegwin Party", "ペンギンプールパーティー", HUNT));
		roundNames.put("FallGuy_PipedUp", new RoundDef("Pipe Dream", "パイプドリーム", RACE));
		roundNames.put("FallGuy_Tunnel_Race_01", new RoundDef("Roll On", "ロールオン", RACE));
		roundNames.put("FallGuy_SeeSaw_variant2", new RoundDef("See Saw", "シーソーゲーム", RACE));
		roundNames.put("FallGuy_ShortCircuit", new RoundDef("Short Circuit", "ショート・サーキット", RACE));
		roundNames.put("FallGuy_SkeeFall", new RoundDef("Ski Fall", "スキーフォール", HUNT));
		roundNames.put("FallGuy_Gauntlet_06", new RoundDef("Skyline Stumble", "スカイラインスタンブル", RACE));
		roundNames.put("FallGuy_Lava_02", new RoundDef("Slime Climb", "スライムクライム", RACE));
		roundNames.put("FallGuy_SlimeClimb_2", new RoundDef("Slimescraper", "スライムスクレイパー", RACE));
		roundNames.put("FallGuy_TipToe", new RoundDef("Tip Toe", "ヒヤヒヤロード", RACE));
		roundNames.put("FallGuy_Gauntlet_07", new RoundDef("Treetop Tumble", "ツリートップタンブル", RACE));
		roundNames.put("FallGuy_Gauntlet_05", new RoundDef("Tundra Run", "ツンドラダッシュ", RACE));
		roundNames.put("FallGuy_Gauntlet_03", new RoundDef("Whirlygig", "グルグルファイト", RACE));
		roundNames.put("FallGuy_WallGuys", new RoundDef("Wall Guys", "ウォールガイズ", RACE));
		roundNames.put("FallGuy_FruitPunch", new RoundDef("Big Shots", "ビッグショット", SURVIVAL));
		roundNames.put("FallGuy_Block_Party", new RoundDef("Block Party", "ブロックパーティー", SURVIVAL));
		roundNames.put("FallGuy_HoverboardSurvival", new RoundDef("Hoverboard Heroes", "ホバーボードヒーローズ", SURVIVAL));
		roundNames.put("FallGuy_JumpClub_01", new RoundDef("Jump Club", "ジャンプクラブ", SURVIVAL));
		roundNames.put("FallGuy_MatchFall", new RoundDef("Perfect Match", "パーフェクトマッチ", LOGIC));
		roundNames.put("FallGuy_Tunnel_01", new RoundDef("Roll Out", "ロールアウト", SURVIVAL));
		roundNames.put("FallGuy_SnowballSurvival", new RoundDef("Snowball Survival", "雪玉サバイバル", SURVIVAL));
		roundNames.put("FallGuy_RobotRampage_Arena2", new RoundDef("Stompin' Ground", "ストンピングラウンド", SURVIVAL));
		roundNames.put("FallGuy_FruitBowl", new RoundDef("Sum Fruit", "カウントフルーツ", LOGIC));
		roundNames.put("FallGuy_TailTag_2", new RoundDef("Tail Tag", "しっぽオニ", HUNT));
		roundNames.put("FallGuy_Basketfall_01", new RoundDef("Basketfall", "バスケットフォール", TEAM));
		roundNames.put("FallGuy_EggGrab", new RoundDef("Egg Scramble", "エッグスクランブル", TEAM));
		roundNames.put("FallGuy_EggGrab_02", new RoundDef("Egg Siege", "エッグキャッスル", TEAM));
		roundNames.put("FallGuy_FallBall_5", new RoundDef("Fall Ball", "フォールボール", TEAM));
		roundNames.put("FallGuy_BallHogs_01", new RoundDef("Hoarders", "ためこみ合戦", TEAM));
		roundNames.put("FallGuy_Hoops_01", new RoundDef("Hoopsie Daisy", "フープ・ループ・ゴール", TEAM));
		roundNames.put("FallGuy_TeamInfected", new RoundDef("Jinxed", "バッドラック", TEAM));
		roundNames.put("FallGuy_ChickenChase_01", new RoundDef("Pegwin Pursuit", "ペンギンチェイス", TEAM));
		roundNames.put("FallGuy_TerritoryControl_v2", new RoundDef("Power Trip", "パワートリップ", TEAM));
		roundNames.put("FallGuy_RocknRoll", new RoundDef("Rock'N'Roll", "ロックンロール", TEAM));
		roundNames.put("FallGuy_Snowy_Scrap", new RoundDef("Snowy Scrap", "スノースクラップ", TEAM));
		roundNames.put("FallGuy_Invisibeans", new RoundDef("Sweet Thieves", "キャンディードロボー", TEAM));
		roundNames.put("FallGuy_ConveyorArena_01", new RoundDef("Team Tail Tag", "チームしっぽオニ", TEAM));
		roundNames.put("FallGuy_FallMountain_Hub_Complete", new RoundDef("Fall Mountain", "フォールマウンテン", RACE));
		roundNames.put("FallGuy_FloorFall", new RoundDef("Hex-A-Gone", "とまるなキケン", SURVIVAL));
		roundNames.put("FallGuy_JumpShowdown_01", new RoundDef("Jump Showdown", "ジャンプ・ショーダウン", SURVIVAL));
		roundNames.put("FallGuy_Crown_Maze_Topdown", new RoundDef("Lost Temple", "ロストテンプル", RACE));
		roundNames.put("FallGuy_Tunnel_Final", new RoundDef("Roll Off", "ロールオフ", SURVIVAL));
		roundNames.put("FallGuy_Arena_01", new RoundDef("Royal Fumble", "ロイヤルファンブル", HUNT));
		roundNames.put("FallGuy_ThinIce", new RoundDef("Thin Ice", "パキパキアイス", SURVIVAL));
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
		return "Final進出者のみ。進出に付き10pt。優勝で更に+20pt。";
	}

	// このラウンドを集計対象とするかどうかを判定
	public boolean isEnable(Round r) {
		if (!r.fixed)
			return false;
		// final のみ
		return r.isFinal;
	}

	// stat.totalScore を設定する。
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		if (r.isFinal) {
			stat.totalScore += 10;
			if (p.win != null && p.win)
				stat.totalScore += 20;
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
		switch (sort) {
		case 0:
			Collections.sort(list, new Core.PlayerComparatorScore());
			break;
		case 1:
			Collections.sort(list, new Core.PlayerComparatorWin());
			break;
		case 2:
			Collections.sort(list, new Core.PlayerComparatorRate());
			break;
		/*
		case 2:
		Collections.sort(list, new PlayerComparatorSameteam());
		break;
		case 3:
		Collections.sort(list, new PlayerComparatorSameteamWin());
		break;
		*/
		}

		StringBuilder buf = new StringBuilder();
		int no = 0;
		for (PlayerStat player : list) {
			if (player.roundCount >= minMatches) {
				no += 1;
				buf.append(Core.pad(no)).append(" ");

				if (sort < 3) {
					buf.append(Core.pad(player.winCount)).append("/").append(Core.pad(player.roundCount))
							.append("(").append(String.format("%6.2f", player.getRate()))
							.append("% pt=" + String.format("%3d", player.totalScore) + ")");
				} else {
					/*
					str = str + pad(player.sameteam) + "/";
					str = str + pad(player.round) + "(";
					String rate_str = String.valueOf(player.getRateSameteam());
					String[] rate_sp = rate_str.split("\\.", 2);
					if (rate_sp[0].length() == 1) {
						rate_str = "0" + rate_str;
					}
					if (rate_sp[1].length() == 1) {
						rate_str = rate_str + "0";
					}
					str = str + rate_str + "%) ";
					*/
				}
				/*
				if (sort == 3) {
					str = str + pad(player.sameteamWin) + "/";
					str = str + pad(player.sameteam) + "/";
					str = str + pad(player.round) + "(";
					String rate_str = String.valueOf(player.getRateSameteamWin());
					String[] rate_sp = rate_str.split("\\.", 2);
					if (rate_sp[0].length() == 1) {
						rate_str = "0" + rate_str;
					}
					if (rate_sp[1].length() == 1) {
						rate_str = rate_str + "0";
					}
					str = str + rate_str + "%) ";
				}
				*/
				buf.append(" ").append(player.name).append("\n");
			}
		}
		return new String(buf);
	}
}

// レース１位 4pt 決勝進出10pt 優勝30pt
class FeedFirstRankingMaker extends RankingMaker {
	public String toString() {
		return "Feed Point";
	}

	public String getDesc() {
		return "race/hunt １位4pt。決勝進出10pt。優勝30pt で計算。";
	}

	// このラウンドを集計対象とするかどうかを判定
	public boolean isEnable(Round r) {
		if (!r.fixed)
			return false;
		if (r.isFinal)
			return true;
		// 順位に意味のある種目のみ
		RoundDef def = RoundDef.get(r.name);
		if (def.type == RoundDef.RACE || def.type == RoundDef.HUNT)
			return true;
		return false;
	}

	// stat.totalScore を設定する。
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		if (r.isFinal) {
			stat.totalScore += 10;
			if (p.win != null && p.win)
				stat.totalScore += 20;
			return;
		}
		if (p.ranking == 1) // 1st
			stat.totalScore += 4;
	}
}

//FallBall Cup のみ
class FallBallRankingMaker extends RankingMaker {
	public String toString() {
		return "FallBall";
	}

	public String getDesc() {
		return "FallBall のみの勝率。";
	}

	// このラウンドを集計対象とするかどうかを判定
	public boolean isEnable(Round r) {
		if (!r.fixed)
			return false;
		// fallball custom round のみ
		if (r.name.equals("FallGuy_FallBall_5"))
			return true;
		return false;
	}

	// stat.totalScore を設定する。
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.totalScore += p.win == Boolean.TRUE ? 1 : 0;
	}
}

// thieves のみの、ガーディアン、シフ別戦績集計
class CandyRankingMaker extends RankingMaker {
	public String toString() {
		return "Sweet Thieves";
	}

	public String getDesc() {
		return "Sweet Thieves 専用集計です。total は切断も込の値。thief/guard はそれぞれのチーム別の戦績で最後までやった試合のデータです。";
	}

	// このラウンドを集計対象とするかどうかを判定
	public boolean isEnable(Round r) {
		if (!r.fixed)
			return false;
		// thieves のみ
		if ("FallGuy_Invisibeans".equals(r.name))
			return true;
		return false;
	}

	// stat.totalScore を設定する。
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		if (p.win == null)
			return; // 結果の出ていないものは集計から除外するか
		boolean isGuard = false;
		boolean myResult = p.win != null && p.win;
		int sameResultPlayers = 0;
		stat.totalScore += myResult ? 1 : 0;

		for (Player o : r.byId.values())
			if (o.win != null && myResult == o.win)
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

	public String getRanking(int sort, int minMatches) {
		List<PlayerStat> list;
		synchronized (Core.listLock) {
			list = new ArrayList<PlayerStat>(Core.stats.values());
		}
		if (list.size() == 0)
			return "";
		switch (sort) {
		case 0:
			Collections.sort(list, new Core.PlayerComparatorScore());
			break;
		case 1:
			Collections.sort(list, new Core.PlayerComparatorWin());
			break;
		case 2:
			Collections.sort(list, new Core.PlayerComparatorRate());
			break;
		/*
		case 2:
		Collections.sort(list, new PlayerComparatorSameteam());
		break;
		case 3:
		Collections.sort(list, new PlayerComparatorSameteamWin());
		break;
		*/
		}

		StringBuilder buf = new StringBuilder();
		int no = 0;
		for (PlayerStat player : list) {
			no += 1;
			buf.append(Core.pad(no)).append(" ").append(player.name).append("\n");
			buf.append("  total: ").append(Core.pad(player.winCount)).append("/").append(Core.pad(player.roundCount))
					.append("(")
					.append(String.format("%6.2f", player.getRate())).append("%)\n");
			buf.append("  thief: ").append(Core.pad(player.getInt("thiefWin")))
					.append("/").append(Core.pad(player.getInt("thiefMatch"))).append("(")
					.append(String.format("%6.2f",
							Core.calRate(player.getInt("thiefWin"), player.getInt("thiefMatch"))))
					.append("%)\n");
			buf.append("  guard: ").append(Core.pad(player.getInt("guardWin")))
					.append("/").append(Core.pad(player.getInt("guardMatch"))).append("(")
					.append(String.format("%6.2f",
							Core.calRate(player.getInt("guardWin"), player.getInt("guardMatch"))))
					.append("%)\n");
		}
		return new String(buf);
	}
}

class Core {
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

	public void load() {

	}

	public void save() {

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
				// round filter
				if (!rankingMaker.isEnable(r))
					continue;

				// このラウンドの参加者の結果を反映
				for (Player p : r.byName.values()) {
					PlayerStat stat = stats.get(p.name);
					if (stat == null) {
						stat = new PlayerStat(p.name);
						stats.put(stat.name, stat);
					}
					stat.roundCount += 1;
					if (p.win != null && p.win)
						stat.winCount += 1;
					rankingMaker.calcTotalScore(stat, p, r);
				}
			}
		}
	}

	static class PlayerComparatorScore implements Comparator<PlayerStat> {
		@Override
		public int compare(PlayerStat p1, PlayerStat p2) {
			return (int) Math.signum(p2.totalScore - p1.totalScore);
		}
	}

	static class PlayerComparatorWin implements Comparator<PlayerStat> {
		@Override
		public int compare(PlayerStat p1, PlayerStat p2) {
			return (int) Math.signum(p2.winCount - p1.winCount);
		}
	}

	static class PlayerComparatorRate implements Comparator<PlayerStat> {
		@Override
		public int compare(PlayerStat p1, PlayerStat p2) {
			return (int) Math.signum(p2.getRate() - p1.getRate());
		}
	}

	/*
	static class PlayerComparatorSameteam implements Comparator<PlayerStat> {
		@Override
		public int compare(PlayerStat p1, PlayerStat p2) {
			return p1.getRateSameteam() > p2.getRateSameteam() ? -1 : 1;
		}
	}

	static class PlayerComparatorSameteamWin implements Comparator<PlayerStat> {
		@Override
		public int compare(PlayerStat p1, PlayerStat p2) {
			return p1.getRateSameteamWin() > p2.getRateSameteamWin() ? -1 : 1;
		}
	}
	*/
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
	int readState = 0;
	int goalCount = 0;
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

	static final int SHOW_DETECTING = 0;
	static final int ROUND_DETECTING = 1;
	static final int MEMBER_DETECTING = 2;
	static final int RESULT_DETECTING = 3;

	private void parseLine(String line) {
		Matcher m = patternServer.matcher(line);
		if (m.find()) {
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
						// TODO 自動生成された catch ブロック
						e.printStackTrace();
					}
				}
			}
		}
		m = patternMatchStart.matcher(line);
		if (m.find()) {
			String showName = m.group(1);
			long id = (int) (Math.random() * 65536);
			Core.addMatch(new Match(showName, id));
			System.out.println("DETECT SHOW STARTING " + showName);
			readState = ROUND_DETECTING;
			listener.showUpdated();
		}
		m = patternMatchStart2.matcher(line);
		if (m.find()) {
			String showName = "_";
			long id = (int) (Math.random() * 65536);
			Core.addMatch(new Match(showName, id));
			System.out.println("DETECT SHOW STARTING squad ?");
			readState = ROUND_DETECTING;
			listener.showUpdated();
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
				long frame = Long.parseUnsignedLong(m.group(2));
				Core.addRound(new Round(roundName, frame, isFinal));
				System.out.println("DETECT STARTING " + roundName + " frame=" + frame);
				readState = MEMBER_DETECTING;
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
			if (line.contains("[StateGameLoading] Starting the game.")) {
				System.out.println("DETECT STARTING GAME");
				Core.getCurrentRound().start = getTime(line);
				listener.roundStarted();
				goalCount = eliminatedCount = 0; // reset
				readState = RESULT_DETECTING;
				break;
			}
			if (line.contains("[StateMainMenu] Creating or joining lobby")
					|| line.contains("[StateMatchmaking] Begin matchmaking")) {
				System.out.println("DETECT BACK TO LOBBY");
				Core.rounds.remove(Core.rounds.size() - 1); // delete current round
				readState = SHOW_DETECTING;
				break;
			}
			break;
		case RESULT_DETECTING: // result detection
			m = patternPlayerResult.matcher(line);
			if (m.find()) {
				int playerId = Integer.parseUnsignedInt(m.group(1));
				boolean succeeded = "True".equals(m.group(2));
				Player player = Core.getCurrentRound().byId.get(playerId);
				if (!succeeded)
					System.out.print("Eliminated for " + playerId + " ");
				if (player != null) {
					player.win = succeeded;
					if (succeeded) {
						player.score = Core.getCurrentRound().byId.size() - goalCount;
						goalCount += 1;
						player.ranking = goalCount;
						System.out.println("Qualified " + player + " rank=" + player.ranking);
					} else {
						player.score = -(Core.getCurrentRound().byId.size() - eliminatedCount);
						player.ranking = Core.getCurrentRound().byId.size() - eliminatedCount;
						eliminatedCount += 1;
						System.out.println(player);
					}
					listener.roundUpdated();
				}
				break;
			}
			// round over より後に出力されている。
			m = patternPlayerResult2.matcher(line);
			if (m.find()) {
				int playerId = Integer.parseUnsignedInt(m.group(1));
				int score = Integer.parseUnsignedInt(m.group(2));
				boolean isFinal = "True".equals(m.group(3));
				Player player = Core.getCurrentRound().byId.get(playerId);
				System.out.println(
						"Result for " + playerId + " score=" + score + " isFinal=" + isFinal + " " + player);
				if (player != null) {
					//player.win = ;
					player.score = score;
				}
				break;
			}
			//if (text.contains("[ClientGameManager] Server notifying that the round is over.")
			if (line.contains("[GameSession] Changing state from Playing to GameOver")) {
				System.out.println("DETECT END GAME");
				Core.getCurrentRound().fixed = true;
				Core.getCurrentRound().end = getTime(line);
				Core.updateStats();
				listener.roundDone();
				readState = ROUND_DETECTING;
				break;
			}
			if (line.contains("== [CompletedEpisodeDto] ==")) {
				// kudos 他はこの後に続く
				readState = SHOW_DETECTING;
				break;
			}
			if (line.contains("[GameStateMachine] Replacing FGClient.StateGameInProgress")
					|| line.contains("[ClientGameManager] Shutdown")
					|| line.contains("[StateMainMenu] Creating or joining lobby")
					|| line.contains("[StateMainMenu] Loading scene MainMenu")
					|| line.contains("[StateMatchmaking] Begin matchmaking")
					|| line.contains("Changing local player state to: SpectatingEliminated")
					|| line.contains("[GlobalGameStateClient] SwitchToDisconnectingState")
					|| line.contains(
							"[GameStateMachine] Replacing FGClient.StatePrivateLobby with FGClient.StateMainMenu")) {
				readState = SHOW_DETECTING;
				break;
			}
			break;
		}
	}
}

// UI
public class FallGuysRecord extends JFrame implements FGReader.Listener {
	static FallGuysRecord frame;
	static FGReader reader;
	static String path_str;
	static String monospacedFontFamily = "MS Gothic";
	static String fontFamily = "Meiryo UI";

	public static void main(String[] args) throws Exception {
		//	UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel");
		//	UIManager.setLookAndFeel("com.sun.java.swing.plaf.motif.MotifLookAndFeel");
		UIManager.setLookAndFeel("com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
		int pt_x = 10;
		int pt_y = 10;
		int size_x = 1280;
		int size_y = 628;
		try (BufferedReader br = new BufferedReader(new FileReader("window_pt_size.ini"))) {
			String str;
			String[] value;
			while ((str = br.readLine()) != null) {
				value = str.split(" ", 4);
				pt_x = Integer.parseInt(value[0]);
				pt_y = Integer.parseInt(value[1]);
				size_x = Integer.parseInt(value[2]);
				size_y = Integer.parseInt(value[3]);
			}
		} catch (FileNotFoundException e) {
		}
		try (BufferedReader br = new BufferedReader(new FileReader("path.ini"))) {
			String str;
			while ((str = br.readLine()) != null) {
				path_str = str;
			}
		}

		frame = new FallGuysRecord();
		frame.setResizable(true);
		frame.setBounds(pt_x, pt_y, size_x, size_y);
		frame.setTitle("Fall Guys Record");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}

	JPanel p;
	JLabel myStatLabel;
	JLabel pingLabel;
	JList<String> matchSel;
	JList<String> roundsSel;
	JTextArea roundResultArea;
	JTextArea rankingArea;
	JComboBox<String> rankingSortSel;
	JComboBox<Integer> rankingFilterSel;
	JComboBox<String> playerSel;
	JButton removeMemberButton;
	JComboBox<RankingMaker> rankingMakerSel;
	JLabel rankingDescLabel;;

	static final int LINE1_Y = 10;
	static final int LINE2_Y = 40;
	static final int LINE4_Y = 498;
	static final int LINE5_Y = 530;
	static final int LINE6_Y = 560;
	static final int COL1_X = 10;
	static final int COL2_X = 400;
	static final int COL3_X = 530;
	static final int COL4_X = 760;

	FallGuysRecord() {
		p = new JPanel();
		p.setLayout(null);

		JLabel label = new JLabel("【総合ランキング】");
		label.setFont(new Font(fontFamily, Font.BOLD, 14));
		label.setBounds(COL1_X, LINE1_Y, 200, 20);
		p.add(label);

		rankingSortSel = new JComboBox<String>();
		rankingSortSel.setFont(new Font(fontFamily, Font.BOLD, 12));
		rankingSortSel.setBounds(COL1_X + 120, LINE1_Y, 95, 20);
		rankingSortSel.addItem("スコア順");
		rankingSortSel.addItem("勝利数順");
		rankingSortSel.addItem("勝率順");
		rankingSortSel.addItemListener(ev -> {
			displayRanking();
		});
		p.add(rankingSortSel);

		rankingFilterSel = new JComboBox<Integer>();
		rankingFilterSel.setFont(new Font(fontFamily, Font.BOLD, 12));
		rankingFilterSel.setBounds(COL1_X + 220, LINE1_Y, 44, 20);
		rankingFilterSel.addItem(0);
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
		label.setBounds(COL1_X + 268, LINE1_Y, 120, 20);
		p.add(label);

		label = new JLabel("【マッチ一覧】");
		label.setFont(new Font(fontFamily, Font.BOLD, 14));
		label.setBounds(COL2_X, LINE1_Y, 100, 20);
		p.add(label);
		label = new JLabel("【ラウンド一覧】");
		label.setFont(new Font(fontFamily, Font.BOLD, 14));
		label.setBounds(COL3_X, LINE1_Y, 200, 20);
		p.add(label);
		label = new JLabel("【ラウンド結果】");
		label.setFont(new Font(fontFamily, Font.BOLD, 14));
		label.setBounds(COL4_X, LINE1_Y, 200, 20);
		p.add(label);

		JScrollPane scroller;
		rankingArea = new JTextArea();
		rankingArea.setFont(new Font(monospacedFontFamily, Font.PLAIN, 14));
		p.add(scroller = new JScrollPane(rankingArea));
		scroller.setBounds(COL1_X, LINE2_Y, 380, 480);

		rankingMakerSel = new JComboBox<RankingMaker>();
		rankingMakerSel.setFont(new Font(fontFamily, Font.BOLD, 12));
		rankingMakerSel.setBounds(COL1_X, LINE5_Y, 150, 25);
		p.add(rankingMakerSel);
		rankingMakerSel.addItem(new RankingMaker());
		rankingMakerSel.addItem(new FeedFirstRankingMaker());
		rankingMakerSel.addItem(new FallBallRankingMaker());
		rankingMakerSel.addItem(new CandyRankingMaker());
		rankingMakerSel.addItemListener(ev -> {
			Core.rankingMaker = (RankingMaker) rankingMakerSel.getSelectedItem();
			rankingDescLabel.setText(Core.rankingMaker.getDesc());
			Core.updateStats();
			displayRanking();
		});
		rankingDescLabel = new JLabel(Core.rankingMaker.getDesc());
		rankingDescLabel.setFont(new Font(fontFamily, Font.PLAIN, 14));
		rankingDescLabel.setBounds(COL1_X + 160, LINE5_Y, 800, 20);
		p.add(rankingDescLabel);

		matchSel = new JList<String>(new DefaultListModel<String>());
		matchSel.setFont(new Font(fontFamily, Font.PLAIN, 16));
		p.add(scroller = new JScrollPane(matchSel));
		scroller.setBounds(COL2_X, LINE2_Y, 120, 450);
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
		scroller.setBounds(COL3_X, LINE2_Y, 220, 450);
		roundsSel.addListSelectionListener((ev) -> {
			if (ev.getValueIsAdjusting()) {
				// The user is still manipulating the selection.
				return;
			}
			roundSelected(getSelectedRound());
		});

		roundResultArea = new JTextArea();
		roundResultArea.setFont(new Font(monospacedFontFamily, Font.PLAIN, 16));
		p.add(scroller = new JScrollPane(roundResultArea));
		scroller.setBounds(COL4_X, LINE2_Y, 494, 450);

		playerSel = new JComboBox<String>();
		playerSel.setFont(new Font(fontFamily, Font.BOLD, 12));
		playerSel.setBounds(COL3_X, LINE4_Y, 150, 25);
		p.add(playerSel);

		removeMemberButton = new JButton("ラウンドから参加者を外す");
		removeMemberButton.setFont(new Font(fontFamily, Font.BOLD, 14));
		removeMemberButton.addActionListener(ev -> removePlayerOnCurrentMatch());
		p.add(removeMemberButton);
		removeMemberButton.setBounds(COL3_X + 160, LINE4_Y, 200, 25);

		myStatLabel = new JLabel("0勝 / 0試合 (0.0%)");
		myStatLabel.setFont(new Font(fontFamily, Font.BOLD, 20));
		myStatLabel.setBounds(COL1_X, LINE6_Y, 300, 20);
		p.add(myStatLabel);

		pingLabel = new JLabel("PING:");
		pingLabel.setFont(new Font(fontFamily, Font.PLAIN, 16));
		pingLabel.setBounds(COL4_X, LINE6_Y, 300, 20);
		p.add(pingLabel);

		Container contentPane = getContentPane();
		contentPane.add(p);

		this.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				try {
					File file = new File("window_pt_size.ini");
					PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file)));
					Point pt = frame.getLocationOnScreen();
					Dimension size = frame.getSize();
					pw.print(pt.x + " " + pt.y + " " + size.width + " " + size.height);
					pw.close();
				} catch (IOException e1) {
				}
				try {
					File file = new File("result.txt");
					FileWriter filewriter = new FileWriter(file, true);

					Date d = new Date();
					SimpleDateFormat d1 = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
					filewriter.write("[" + d1.format(d) + "] " + myStatLabel.getText() + "\n");
					filewriter.close();
				} catch (IOException e1) {
				}
			}
		});

		// start log read
		reader = new FGReader(new File(path_str), this);
		reader.start();
	}

	void updateMatches() {
		pingLabel.setText("PING: " + Core.pingMS + "ms(" + Core.serverIp + ")");
		DefaultListModel<String> model = (DefaultListModel<String>) matchSel.getModel();
		model.clear();
		model.addElement("ALL");
		synchronized (Core.listLock) {
			for (Match m : Core.matches) {
				model.addElement(m.name);
			}
			matchSel.setSelectedIndex(0);
			matchSel.ensureIndexIsVisible(matchSel.getSelectedIndex());
		}
	}

	void updateRounds() {
		DefaultListModel<String> model = (DefaultListModel<String>) roundsSel.getModel();
		model.clear();
		synchronized (Core.listLock) {
			for (Round r : Core.rounds) {
				model.addElement(RoundDef.get(r.name).dispNameJa);
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
				model.addElement(RoundDef.get(r.name).dispNameJa);
			}
		}
		roundsSel.setSelectedIndex(model.size() - 1);
		roundsSel.ensureIndexIsVisible(roundsSel.getSelectedIndex());
	}

	void roundSelected(Round r) {
		if (r == null) {
			roundResultArea.setText("");
			return;
		}
		updatePlayerSel(r);
		StringBuilder buf = new StringBuilder();
		if (r.isFinal)
			buf.append("********** FINAL ***********\n");
		synchronized (Core.listLock) {
			for (Player p : r.byRank()) {
				buf.append(p.win == null ? "　" : p.win ? "○" : "✕");
				buf.append(Core.pad(p.ranking));
				if (p.squadId > 0)
					buf.append(" sq=").append(Core.pad(p.squadId)).append(" ");
				buf.append(" pt=").append(Core.pad(p.score)).append("\t");
				buf.append(p.name).append("\n");
			}
		}
		roundResultArea.setText(new String(buf));
		roundResultArea.setCaretPosition(0);
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
			updatePlayerSel(Core.getCurrentRound());
			updateRounds();
		});
	}

	@Override
	public void roundUpdated() {
		SwingUtilities.invokeLater(() -> {
			roundSelected(getSelectedRound());
		});
	}

	@Override
	public void roundDone() {
		SwingUtilities.invokeLater(() -> {
			updateRounds();
		});
	}

	Round getSelectedRound() {
		synchronized (Core.listLock) {
			if (matchSel.getSelectedIndex() < 0 || roundsSel.getSelectedIndex() < 0)
				return null;
			if (matchSel.getSelectedIndex() == 0)
				return Core.rounds.get(roundsSel.getSelectedIndex());
			return Core.matches.get(matchSel.getSelectedIndex() - 1).rounds.get(roundsSel.getSelectedIndex());
		}
	}

	public void updatePlayerSel(Round r) {
		playerSel.removeAllItems();
		synchronized (Core.listLock) {
			for (Player player : r.byName.values()) {
				FallGuysRecord.frame.playerSel.addItem(player.name);
			}
		}
		//playerSel.setSelectedItem(Core.myName);
	}

	private void removePlayerOnCurrentMatch() {
		String name_selected = (String) playerSel.getSelectedItem();
		Round r = getSelectedRound();
		r.remove(name_selected);
		Core.updateStats();
		roundSelected(r);
		displayRanking();
	}

	void displayRanking() {
		rankingArea.setText(Core.rankingMaker.getRanking(
				rankingSortSel.getSelectedIndex(),
				(Integer) rankingFilterSel.getSelectedItem()));
		rankingArea.setCaretPosition(0);
		PlayerStat own = Core.getMyStat();
		if (own != null)
			myStatLabel.setText(own.winCount + "勝 / " + own.roundCount + "試合 (" + own.getRate() + "%)");
	}
}
