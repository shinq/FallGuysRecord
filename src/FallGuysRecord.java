import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.ResourceBundle.Control;
import java.util.Set;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import javax.swing.AbstractListModel;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.RowSorter.SortKey;
import javax.swing.SortOrder;
import javax.swing.SpringLayout;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// 集計後のプレイヤーごと
// もともと全プレイヤーの情報を収集できるデザインだったが、今は実質自分のみ。
class PlayerStat {
	int participationCount; // rate 分母(今回log分)。round または match 数。RankingMaker による。
	int winCount; // rate 分子(今回log分)。優勝やクリアなど。RankingMaker による。
	int totalParticipationCount; // rate 分母。round または match 数。RankingMaker による。
	int totalWinCount; // rate 分子。優勝やクリアなど。RankingMaker による。

	Map<String, String> additional = new HashMap<String, String>(); // 独自の統計を使う場合用領域

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

	public boolean setWin(Round r, boolean win) {
		if (win) {
			if (r.match.isCurrentSession())
				winCount += 1;
			totalWinCount += 1;
		} else {
		}
		return win;
	}

	public void reset() {
		totalParticipationCount = totalWinCount = participationCount = winCount = 0;
	}
}

//各ラウンドのプレイヤー戦績
class Player {
	Round round;
	String name; // for user identication
	String platform;
	int id; // id of current round (diferrent for each rounds)
	int objectId; // object id of current round (for score log)
	int squadId;
	int partyId;
	int teamId;
	int ranking; // rank of current round

	Date finish; // goal or eliminated time
	Boolean qualified;
	int score; // ラウンド中のスコアあるいは順位スコア
	int finalScore = -1; // ラウンド終了後に出力されたスコア

	Player(int id) {
		this.id = id;
	}

	// squad を考慮したクリア/脱落
	boolean isQualified() {
		if (squadId == 0)
			return qualified == Boolean.TRUE;

		// FIXME: パーティメンバー全員 qualified でも順位が規定以下なら eliminated になる辺りをどうするか。
		// 次のラウンドにいるかどうかくらいでしか判定できない気がする…
		// 決勝ならメンバーの誰かに優勝者がいれば優勝とみなす、でいいのだが…
		// 今は決勝以外では正確な qualified を返せていない
		for (Player member : round.getSquad(squadId).members) {
			if (member.qualified == Boolean.TRUE)
				return true;
		}
		return false;
	}

	public String toString() {
		return name + "(" + platform + ")";
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
			// ログ出力スコアを信用
			if (p.finalScore >= 0 && !p.round.getDef().isHuntRace())
				score += p.finalScore;
			else {
				score += p.score;
				// hunt-race の場合のみ順位スコア*10を加算して順位狂わないように調整
				if (p.round.getDef().isHuntRace() && p.ranking > 0)
					score += (p.round.playerCount - p.ranking) * 10;
			}
		}
		return score;
	}
}

class Round implements Comparable<Round> {
	final Match match;
	final String name;
	boolean isFinal;
	String roundName2; // より詳細な内部名
	boolean fixed; // ステージ完了まで読み込み済み
	int no; // 0 origin
	Date start;
	Date end;
	Date topFinish;
	int myPlayerId;
	int[] teamScore;
	int playerCount;
	int playerCountAdd;
	boolean playerCountAddChanged;
	boolean disableMe;
	int qualifiedCount;
	Map<String, Player> byName = new HashMap<String, Player>();
	Map<Integer, Player> byId = new HashMap<Integer, Player>();
	// creative
	String creativeCode;
	int creativeVersion;

	public Round(String name, int no, Date id, boolean isFinal, Match match) {
		this.name = name;
		this.no = no;
		start = id;
		this.isFinal = isFinal;
		this.match = match;
	}

	public RoundDef getDef() {
		return RoundDef.get(name);
	}

	public String getName() {
		if (name.startsWith("FallGuy_FraggleBackground"))
			return roundName2;
		return RoundDef.get(name).getName();
	}

	public void add(Player p) {
		p.round = this;
		synchronized (Core.listLock) {
			if (!byId.containsKey(p.id))
				playerCount += 1;
			byId.put(p.id, p);
			if (p.name != null)
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
			playerCount -= 1;
		}
	}

	public Player getByObjectId(int id) {
		for (Player p : byId.values())
			if (p.objectId == id)
				return p;
		return null;
	}

	public boolean isSquad() {
		if (match.isSquad())
			return true;
		return byId.size() > 0 && byId.values().iterator().next().squadId != 0;
	}

	public int getTeamScore(int teamId) {
		if (teamScore == null || teamScore.length <= teamId)
			return 0;
		return teamScore[teamId];
	}

	public boolean isEnabled() {
		Player p = getMe();
		return p != null && !disableMe;
	}

	public boolean isDate(int dayKey) {
		return dayKey == Core.toDayKey(start);
	}

	public int getSubstancePlayerCount() {
		return playerCount + playerCountAdd;
	}

	public int getSubstanceQualifiedCount() {
		return qualifiedCount * 2 > playerCount ? qualifiedCount + playerCountAdd : qualifiedCount;
	}

	// 自分がクリアしたかどうか
	public Player getMe() {
		return byName.get("YOU");
	}

	public boolean isQualified() {
		Player p = getMe();
		return p != null && p.qualified == Boolean.TRUE;
	}

	// myFinish や end を渡して ms 取得
	public long getTime(Date o) {
		if (start == null)
			return 0;
		long t = o.getTime() - start.getTime();
		if (t < 0)
			t += 24 * 60 * 60 * 1000;
		return t;
	}

	public ArrayList<Player> byRank() {
		ArrayList<Player> list = new ArrayList<Player>(byId.values());
		Collections.sort(list, new Core.PlayerComparator(getDef().isHuntRace()));
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
			Collections.sort(s.members, new Core.PlayerComparator(getDef().isHuntRace()));

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

	public boolean isFinal() {
		if (isFinal)
			return true;
		// isFinal だけでは決勝判定が不十分…
		if (roundName2 != null) {
			// 非ファイナルラウンドがファイナルとして出現した場合の検査
			if (roundName2.contains("_non_final"))
				return false;
			if (roundName2.contains("only_finals"))
				return false;
			if (roundName2.contains("_final"))
				return true;
			if (roundName2.contains("round_robotrampage_arena_2_ss2_show1_03"))
				return true;
			if (byId.size() > 8 && roundName2.contains("_survival"))
				return false;
			if (roundName2.contains("round_thin_ice_blast_ball_banger"))
				return false;
			//* squads final detection
			if (match.name.startsWith("squads_4") && byId.size() < 9) {
				if (roundName2.startsWith("round_jinxed_squads"))
					return true;
				if (roundName2.startsWith("round_territory_control_squads"))
					return true;
				if (roundName2.startsWith("round_fall_ball_squads"))
					return true;
				if (roundName2.startsWith("round_basketfall_squads"))
					return true;
				if ("round_territory_control_s4_show_squads".equals(roundName2))
					return true;
			}
			//*/
			if ("round_sports_suddendeath_fall_ball_02".equals(roundName2)) // GG
				return true;

			// FIXME: ファイナル向けラウンドが非ファイナルで出現した場合の検査が必要
			if ("round_thin_ice_pelican".equals(roundName2))
				return false;
			if (roundName2.matches("round_floor_fall_.*_0[12]$")) // hex trial
				return false;
			if (roundName2.matches("round_thin_ice_.*_0[12]$")) // thin ice trial
				return false;
			if (roundName2.matches("round_hexaring_.*_0[12]$")) // hexaring trial
				return false;
			if (roundName2.matches("round_blastball_.*_0[12]$")) // blastball trial
				return false;
			if (roundName2.matches("round_.+_event_.+")) // walnut event
				return false;
		}
		/*
		RoundDef def = RoundDef.get(name);
		if (def != null && def.isFinalNormally) // 通常ファイナルでしかでないステージならファイナルとみなす。
			return true;
		*/
		return false;
	}

	@Override
	public int hashCode() {
		return start.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		Round o = (Round) obj;
		return start.equals(o.start);
	}

	@Override
	public int compareTo(Round o) {
		return start.compareTo(o.start);
	}

	public String toOverviewString() {
		Player p = getMe();
		StringBuilder buf = new StringBuilder();
		if (start == null)
			return getName();
		buf.append(Core.datef.format(start));
		if (p != null) {
			buf.append(" ").append(p.isQualified() ? "○" : "☓");
			if (p.isQualified())
				buf.append(Core.pad(getSubstanceQualifiedCount())).append("vs")
						.append(Core.pad(getSubstancePlayerCount() - getSubstanceQualifiedCount()));
			else
				buf.append(Core.pad(getSubstancePlayerCount() - getSubstanceQualifiedCount())).append("vs")
						.append(Core.pad(getSubstanceQualifiedCount()));
		}
		buf.append("(").append(Core.pad(playerCountAdd)).append(")");
		if (p != null)
			buf.append(" ").append(getTeamScore(p.teamId)).append(":")
					.append(getTeamScore(1 - p.teamId));
		if (getMe().finish != null)
			buf.append(" ").append(String.format("%.3f", (double) getTime(getMe().finish) / 1000)).append("s");
		buf.append(" ").append(match.ip);
		buf.append(" ").append(match.pingMS).append("ms");
		if (disableMe)
			buf.append(" ☓");
		return new String(buf);
	}

	@Override
	public String toString() {
		return getName();
	}
}

// 一つのショー
class Match {
	final long session; // 起動日時ベース
	String name;
	final Date start; // id として使用
	final String ip;
	long pingMS;
	boolean isCustom;
	boolean fixed; // 完了まで読み込み済み
	Date end;
	int winStreak;
	List<Round> rounds = new ArrayList<Round>();

	public Match(long session, String name, Date start, String ip, boolean isCustom) {
		this.session = session;
		this.name = name;
		this.start = start;
		this.ip = ip;
		this.isCustom = isCustom;
	}

	public void addRound(Round r) {
		synchronized (Core.listLock) {
			int i = rounds.indexOf(r);
			if (i >= 0) {
				rounds.remove(i);
				rounds.add(i, r);
			} else
				rounds.add(r);
		}
	}

	public void finished(Date end) {
		this.end = end;

		if (rounds.size() == 0)
			return;
		// 優勝なら match に勝利数書き込み
		Round last = rounds.get(rounds.size() - 1);
		Player p = last.getMe();
		if (p != null && p.isQualified() && last.isFinal()) {
			winStreak = 1;
			List<Match> matches = Core.matches;
			if (matches.size() > 1)
				Core.currentMatch.winStreak += Core.matches.get(Core.matches.size() - 2).winStreak;
		}
	}

	public boolean isCurrentSession() {
		return session == Core.currentSession;
	}

	public boolean isSquad() {
		return name.contains("squad");
	}

	public boolean isDate(int dayKey) {
		return dayKey == Core.toDayKey(start);
	}

	boolean isMainShow() {
		return "solo_show".equals(name);
	}

	boolean isWin() {
		if (rounds.size() == 0)
			return false;
		Round r = rounds.get(rounds.size() - 1);
		return r.isFinal() && r.isQualified();
	}

	@Override
	public int hashCode() {
		return start.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		Match o = (Match) obj;
		return start.equals(o.start);
	}

	@Override
	public String toString() {
		return name;
	}
}

enum RoundType {
	RACE, HUNT_SURVIVE, HUNT_RACE, SURVIVAL, TEAM
};

class RoundDef {

	public final String key;
	public final RoundType type;
	public final int teamCount;

	public RoundDef(String key, RoundType type) {
		this(key, type, false, 1);
	}

	// for team
	public RoundDef(String key, int teamCount) {
		this(key, RoundType.TEAM, false, teamCount);
	}

	public RoundDef(String key, RoundType type, boolean isFinal) {
		this(key, type, isFinal, 1);
	}

	private RoundDef(String key, RoundType type, boolean isFinal, int teamCount) {
		this.key = key;
		this.type = type;
		//this.isFinalNormally = isFinal;
		this.teamCount = teamCount;
	}

	public String getName() {
		return Core.getRes(key);
	}

	public boolean isHuntRace() {
		return type == RoundType.HUNT_RACE;
	}

	static void add(RoundDef def) {
		roundNames.put(def.key, def);
	}

	static Map<String, RoundDef> roundNames = new HashMap<String, RoundDef>();
	static {
		add(new RoundDef("FallGuy_DoorDash", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_03", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_02_01", RoundType.RACE));
		add(new RoundDef("FallGuy_ChompChomp_01", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_01", RoundType.RACE));
		add(new RoundDef("FallGuy_SeeSaw_variant2", RoundType.RACE));
		add(new RoundDef("FallGuy_Lava_02", RoundType.RACE));
		add(new RoundDef("FallGuy_DodgeFall", RoundType.RACE));
		add(new RoundDef("FallGuy_TipToe", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_04", RoundType.RACE));
		add(new RoundDef("FallGuy_WallGuys", RoundType.RACE));
		add(new RoundDef("FallGuy_BiggestFan", RoundType.RACE));
		add(new RoundDef("FallGuy_IceClimb_01", RoundType.RACE));
		add(new RoundDef("FallGuy_Tunnel_Race_01", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_06", RoundType.RACE));
		add(new RoundDef("FallGuy_ShortCircuit", RoundType.RACE));
		add(new RoundDef("FallGuy_HoverboardSurvival", RoundType.RACE));
		add(new RoundDef("FallGuy_SlimeClimb_2", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_07", RoundType.RACE));
		add(new RoundDef("FallGuy_DrumTop", RoundType.RACE));
		add(new RoundDef("FallGuy_SeeSaw360", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_08", RoundType.RACE));
		add(new RoundDef("FallGuy_PipedUp", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_05", RoundType.RACE));

		add(new RoundDef("FallGuy_TailTag_2", 1));
		add(new RoundDef("FallGuy_1v1_ButtonBasher", RoundType.HUNT_SURVIVE));
		add(new RoundDef("FallGuy_Hoops_Blockade", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_SkeeFall", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_Penguin_Solos", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_KingOfTheHill2", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_Airtime", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_FollowTheLeader", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_FollowTheLeader_UNPACKED", RoundType.HUNT_RACE));

		add(new RoundDef("FallGuy_Block_Party", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_JumpClub_01", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_MatchFall", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_Tunnel_01", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_SnowballSurvival", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_FruitPunch", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_RobotRampage_Arena2", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_FruitBowl", RoundType.SURVIVAL));

		add(new RoundDef("FallGuy_TeamInfected", 2));
		add(new RoundDef("FallGuy_FallBall_5", 2));
		add(new RoundDef("FallGuy_Basketfall_01", 2));
		add(new RoundDef("FallGuy_TerritoryControl_v2", 2));
		add(new RoundDef("FallGuy_BallHogs_01", 3));
		add(new RoundDef("FallGuy_RocknRoll", 3));
		add(new RoundDef("FallGuy_Hoops_01", 3));
		add(new RoundDef("FallGuy_EggGrab", 3));
		add(new RoundDef("FallGuy_EggGrab_02", 3));
		add(new RoundDef("FallGuy_Snowy_Scrap", 3));
		add(new RoundDef("FallGuy_ChickenChase_01", 3));
		add(new RoundDef("FallGuy_ConveyorArena_01", 4));

		add(new RoundDef("FallGuy_Invisibeans", 2));
		add(new RoundDef("FallGuy_PumpkinPie", 2));

		add(new RoundDef("FallGuy_FallMountain_Hub_Complete", RoundType.RACE, true));
		add(new RoundDef("FallGuy_FloorFall", RoundType.SURVIVAL, true));
		add(new RoundDef("FallGuy_JumpShowdown_01", RoundType.SURVIVAL, true));
		add(new RoundDef("FallGuy_Crown_Maze_Topdown", RoundType.RACE, true));
		add(new RoundDef("FallGuy_Tunnel_Final", RoundType.SURVIVAL, true));
		add(new RoundDef("FallGuy_Arena_01", RoundType.HUNT_SURVIVE, true));
		add(new RoundDef("FallGuy_ThinIce", RoundType.SURVIVAL, true));

		add(new RoundDef("FallGuy_Gauntlet_09", RoundType.RACE));
		add(new RoundDef("FallGuy_ShortCircuit2", RoundType.RACE));
		add(new RoundDef("FallGuy_SpinRing", RoundType.SURVIVAL));
		add(new RoundDef("FallGuy_HoopsRevenge", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_1v1_Volleyfall", RoundType.HUNT_SURVIVE));
		add(new RoundDef("FallGuy_HexARing", RoundType.SURVIVAL, true));
		add(new RoundDef("FallGuy_BlastBall_ArenaSurvival", RoundType.SURVIVAL, true));

		add(new RoundDef("FallGuy_BlueJay_UNPACKED", RoundType.HUNT_RACE));

		add(new RoundDef("FallGuy_SatelliteHoppers", RoundType.RACE));
		add(new RoundDef("FallGuy_Gauntlet_10", RoundType.RACE));
		add(new RoundDef("FallGuy_Starlink", RoundType.RACE));
		add(new RoundDef("FallGuy_Hoverboard_Survival_2", RoundType.RACE));
		add(new RoundDef("FallGuy_PixelPerfect", RoundType.HUNT_RACE));
		add(new RoundDef("FallGuy_FFA_Button_Bashers", RoundType.HUNT_RACE));

		add(new RoundDef("FallGuy_Tip_Toe_Finale", RoundType.RACE, true));
		add(new RoundDef("FallGuy_HexSnake", RoundType.SURVIVAL, true));
		// round_tiptoefinale

		add(new RoundDef("FallGuy_SlideChute", RoundType.RACE, false));
		add(new RoundDef("FallGuy_ wTheLine", RoundType.RACE, false));
		add(new RoundDef("FallGuy_SlippySlide", RoundType.HUNT_RACE, false));
		add(new RoundDef("FallGuy_BlastBallRuins", RoundType.SURVIVAL, false));
		add(new RoundDef("FallGuy_Kraken_Attack", RoundType.SURVIVAL, true));
	}

	public static RoundDef get(String name) {
		RoundDef def = roundNames.get(name);
		if (def == null)
			return new RoundDef(name, RoundType.RACE); // unknown stage
		return def;
	}
}

class CreativeMeta {
	String code;
	int version;
	String author;
	String title;
	String description;
	int playerLimit;
	String tag;
	String thumb;
	String userTag = ""; // ユーザが指定する想定
	int userDifficulty; // ユーザが指定する想定の難易度
	int userScore; // ユーザが指定する想定の評価値
	String userComment = ""; // ユーザのフリー記入欄
	int timeLimitSec;
	int playCount;
	int likes;
	int dislikes;
	long clearMS;
	Date played; // 初回記録日時
	Date lastPlayed; // 最終プレイ日時

	@Override
	public boolean equals(Object o) {
		return code.equals(((CreativeMeta) o).code);
	}

	@Override
	public int hashCode() {
		return code.hashCode();
	}

	@Override
	public String toString() {
		return code;
	}
}

///////////////////////////////////////////////////////////////////////////////////////////////////////
interface RoundFilter {
	boolean isEnabled(Round r);
}

class AllRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return r.getMe() != null;
	}

	@Override
	public String toString() {
		return Core.getRes("ALL");
	}
}

class CurrentSessionRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return r.getMe() != null && r.match.isCurrentSession();
	}

	@Override
	public String toString() {
		return Core.getRes("CurrentSesionOnly");
	}
}

class CustomRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return r.getMe() != null && r.match.isCustom;
	}

	@Override
	public String toString() {
		return Core.getRes("CustomOnly");
	}
}

class SoloRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return r.match.isMainShow() && r.getMe() != null;
	}

	@Override
	public String toString() {
		return "SoloOnly";
	}
}

class SquadRoundFilter implements RoundFilter {
	@Override
	public boolean isEnabled(Round r) {
		return r.getMe() != null && r.isSquad();
	}

	@Override
	public String toString() {
		return Core.getRes("SquadOnly");
	}
}

// ソロ優勝回数ランキングをとりあえずデフォルト実装
class RankingMaker {
	public String toString() {
		return "Final/Win";
	}

	public String getDesc() {
		return Core.getRes("finalWinDesc");
	}

	// stat.participationCount / winCount / totalScore を設定する。
	// それぞれをどのような数値にするかは Maker 次第とする。
	// fixed round ごとに呼ばれる。対象外マッチ/ラウンドならなにもしないように実装する。
	// default は優勝数/参加マッチ数。final 進出、優勝でポイント加算。
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		if (r.isFinal()) {
			stat.setWin(r, p.isQualified());
			return;
		}
		if (!p.isQualified())
			stat.setWin(r, false);
	}

	public void calcFinish(PlayerStat stat) {
		stat.participationCount = Core.getMatchCount(o -> o.match.isCurrentSession());
		stat.totalParticipationCount = Core.getMatchCount(Core.filter);
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
		return Core.getRes("squadDesc");
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		if (!r.isSquad())
			return;
		if (r.isFinal()) {
			// メンバーの誰かに優勝者がいれば優勝とみなす。
			for (Player member : r.getSquad(p.squadId).members) {
				if (member.isQualified()) {
					stat.setWin(r, true);
					return;
				}
			}
		}
		// FIXME: スクアッドとしての脱落判定方法。自分が qualidied でなくても通過がありうる。
		if (!p.isQualified())
			stat.setWin(r, false);
	}

	@Override
	public void calcFinish(PlayerStat stat) {
		stat.participationCount = Core.getMatchCount(o -> o.isSquad() && o.match.isCurrentSession());
		stat.totalParticipationCount = Core.getMatchCount(o -> o.isSquad() && Core.filter.isEnabled(o));
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
		return Core.getRes("fallBallDesc");
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		// fallball custom round のみ
		if (!r.name.equals("FallGuy_FallBall_5"))
			return;
		if (r.match.isCurrentSession())
			stat.participationCount += 1; // 参加 round 数
		stat.totalParticipationCount += 1; // 参加 round 数
		stat.setWin(r, p.isQualified());
	}

	@Override
	public void calcFinish(PlayerStat stat) {
	}
}

// 1vs1 のみ
class OneOnOneRankingMaker extends RankingMaker {
	@Override
	public String toString() {
		return "1vs1";
	}

	@Override
	public String getDesc() {
		return Core.getRes("1vs1Desc");
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		// 1v1 stage only
		if (!r.name.equals("FallGuy_1v1_Volleyfall") && !r.name.equals("FallGuy_1v1_ButtonBasher"))
			return;
		if (r.match.isCurrentSession())
			stat.participationCount += 1; // 参加 round 数
		stat.totalParticipationCount += 1; // 参加 round 数
		stat.setWin(r, p.isQualified());
	}

	@Override
	public void calcFinish(PlayerStat stat) {
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
		return Core.getRes("sweetThievesDesc");
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		// thieves のみ
		if (!"FallGuy_Invisibeans".equals(r.name) && !"FallGuy_PumpkinPie".equals(r.name))
			return;
		if (!r.match.isCurrentSession())
			stat.participationCount += 1; // 参加 round 数
		stat.totalParticipationCount += 1; // 参加 round 数
		boolean myResult = p.isQualified();
		stat.setWin(r, myResult);
		if (p.qualified == null)
			return; // 結果の出ていないものは個別集計から除外する
		boolean isGuard = false;
		int sameResultPlayers = 0;

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
	public void calcFinish(PlayerStat stat) {
	}
}

class Core {
	static Locale LANG;
	static ResourceBundle RES;
	static Object listLock = new Object();
	static boolean started = false;

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

	public static String getRes(String key) {
		try {
			return RES.getString(key);
		} catch (Exception e) {
			return key;
		}
	}

	public static String pad(int v) {
		return String.format("%2d", v);
	}

	public static String pad0(int v) {
		return String.format("%02d", v);
	}

	public static int[] intArrayFromString(String string) {
		String[] strings = string.replace("[", "").replace("]", "").split(", ");
		if (strings.length < 2)
			return null;
		int result[] = new int[strings.length];
		for (int i = 0; i < result.length; i++) {
			try {
				result[i] = Integer.parseInt(strings[i]);
			} catch (NumberFormatException ex) {
			}
		}
		return result;
	}

	public static int toDayKey(Date date) {
		Calendar c = Calendar.getInstance();
		c.setTime(date);
		return c.get(Calendar.YEAR) * 10000 + c.get(Calendar.MONTH) * 100 + c.get(Calendar.DAY_OF_MONTH);
	}

	public static int toWeekKey(Date date) {
		Calendar c = Calendar.getInstance();
		c.setTime(date);
		return c.get(Calendar.YEAR) * 100 + c.get(Calendar.WEEK_OF_YEAR);
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
	public static RoundFilter filter = new CurrentSessionRoundFilter();
	public static int limit = 100;
	public static long currentSession;
	public static int currentYear;
	public static int currentMonth;
	public static int currentUTCDate;
	public static String currentServerIp;
	public static RankingMaker rankingMaker = new RankingMaker();
	public static final List<Match> matches = new ArrayList<>();
	public static final List<Round> rounds = new ArrayList<>();
	public static Match currentMatch;
	public static Round currentRound;
	public static List<Round> filtered;
	public static Map<String, Map<String, String>> servers = new HashMap<>();
	public static final List<CreativeMeta> creativesList = new ArrayList<>(); // master
	public static final Map<String, CreativeMeta> creativesMap = new HashMap<>(); // index by code

	static void addCreativeMeta(CreativeMeta meta) {
		if (creativesMap.containsKey(meta.code)) {
			creativesList.remove(meta);
		}
		creativesList.add(meta);
		creativesMap.put(meta.code, meta);
	}

	public static final PlayerStat stat = new PlayerStat();

	public static final SimpleDateFormat datef = new SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN);
	public static final DateFormat dateFormatterMin = new SimpleDateFormat("yyyy/MM/dd HH:mm"); // local time
	public static final DateFormat f = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS");
	public static final DateFormat f2 = new SimpleDateFormat("yyyy/MM/dd HH:mm"); // excel あたりに無理やり変えられた場合の補填用
	static {
		f.setTimeZone(TimeZone.getTimeZone("UTC"));
		f2.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	public static void load() {
		rounds.clear();
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(new FileInputStream("stats.tsv"), StandardCharsets.UTF_8))) {
			String line;
			Match m = null;
			while ((line = in.readLine()) != null) {
				String[] d = line.split("\t", -1);
				if (d.length < 7)
					continue;

				if ("M".equals(d[0])) {
					Date matchStart = f.parse(d[2]);
					boolean isCustom = d.length > 7 && "true".equals(d[7]);
					m = new Match(Long.parseLong(d[1]), d[3], matchStart, d[4], isCustom);
					m.pingMS = Integer.parseInt(d[5]);
					m.winStreak = Integer.parseInt(d[6]);
					addMatch(m);
					continue;
				}
				if (!"r".equals(d[0]) || d.length < 18)
					continue;
				Round r = new Round(d[3], Integer.parseInt(d[2]), f.parse(d[1]), "true".equals(d[5]), m);
				r.roundName2 = d[4];
				r.fixed = true;

				r.playerCount = Integer.parseInt(d[6]);
				r.qualifiedCount = Integer.parseInt(d[7]);
				Player p = new Player(0);
				p.name = "YOU";
				if (d[8].length() > 0)
					p.finish = r.end = new Date(r.start.getTime() + Long.parseUnsignedLong(d[8]));
				p.qualified = "true".equals(d[9]);
				r.disableMe = "true".equals(d[10]);
				r.playerCountAdd = Integer.parseInt(d[11]);
				r.playerCountAddChanged = r.playerCountAdd != 0;
				p.ranking = Integer.parseInt(d[12]);
				p.score = Integer.parseInt(d[13]);
				p.finalScore = Integer.parseInt(d[14]);
				p.partyId = Integer.parseInt(d[15]);
				p.squadId = Integer.parseInt(d[16]);
				p.teamId = Integer.parseInt(d[17]);
				if (d.length > 18)
					r.teamScore = Core.intArrayFromString(d[18]);
				r.add(p);
				addRound(r);
				r.playerCount = Integer.parseInt(d[6]); // reset
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}

		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(new FileInputStream("creatives.tsv"), StandardCharsets.UTF_8))) {
			in.readLine(); // skip first line
			int lineNumber = 1;
			String line;
			while ((line = in.readLine()) != null) {
				lineNumber += 1;
				String[] d = line.split("\t", -1);
				if (d.length < 17) {
					System.err.println("skip line(" + lineNumber + "): " + d);
					continue;
				}
				CreativeMeta meta = new CreativeMeta();
				meta.code = d[0];
				meta.version = Integer.parseInt(d[1]);
				meta.author = d[2];
				meta.title = d[3];
				try {
					meta.clearMS = Integer.parseInt(d[4]);
				} catch (NumberFormatException ex) {
				}
				meta.userTag = d[5];
				try {
					meta.userDifficulty = Integer.parseInt(d[6]);
				} catch (NumberFormatException ex) {
				}
				try {
					meta.userScore = Integer.parseInt(d[7]);
				} catch (NumberFormatException ex) {
				}
				meta.userComment = d[8];
				meta.description = d[9];
				meta.playCount = Integer.parseInt(d[10]);
				meta.likes = Integer.parseInt(d[11]);
				meta.dislikes = Integer.parseInt(d[12]);
				meta.timeLimitSec = Integer.parseInt(d[13]);
				try {
					meta.played = dateFormatterMin.parse(d[14]);
				} catch (ParseException ex) {
				}
				try {
					meta.lastPlayed = dateFormatterMin.parse(d[15]);
				} catch (ParseException ex) {
				}
				meta.tag = d[16];
				if (d.length > 18) {
					meta.playerLimit = Integer.parseInt(d[17]);
					meta.thumb = "".equals(d[18]) ? null : d[18];
				}
				addCreativeMeta(meta);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	public static final byte[] BOM = new byte[] { (byte) 0xef, (byte) 0xbb, (byte) 0xbf };

	public static void save() {
		try {
			Files.copy(Paths.get("creatives.tsv"), Paths.get("creatives_prev.tsv"),
					StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			OutputStream o = new FileOutputStream("creatives.tsv");
			try (PrintWriter out = new PrintWriter(new OutputStreamWriter(o, StandardCharsets.UTF_8), false)) {
				o.write(BOM);
				out.println(
						"Code\tVersion\tAuthor\tTitle\tClearTime\tUserTag\tUserDifficulty\tUserReview\tUserComment\tDesc\tPlayCount\tLikes\tDislikes\tTimeLimit\tRecorded\tLastPlayed\tTag\tPlayerLimit\tthumbnail");
				for (CreativeMeta meta : creativesList) {
					out.print(meta.code); // 0
					out.print("\t");
					out.print(meta.version); // 1
					out.print("\t");
					out.print(meta.author); // 2
					out.print("\t");
					out.print(meta.title); // 3
					out.print("\t");
					out.print(meta.clearMS); // 4
					out.print("\t");
					out.print(meta.userTag); // 5
					out.print("\t");
					out.print(meta.userDifficulty); // 6
					out.print("\t");
					out.print(meta.userScore); // 7
					out.print("\t");
					out.print(meta.userComment); // 8
					out.print("\t");
					out.print(meta.description); // 9
					out.print("\t");
					out.print(meta.playCount); // 10
					out.print("\t");
					out.print(meta.likes); // 11
					out.print("\t");
					out.print(meta.dislikes); // 12
					out.print("\t");
					out.print(meta.timeLimitSec); // 13
					out.print("\t");
					out.print(meta.played == null ? "" : dateFormatterMin.format(meta.played)); // 14
					out.print("\t");
					out.print(meta.lastPlayed == null ? "" : dateFormatterMin.format(meta.lastPlayed)); // 15
					out.print("\t");
					out.print(meta.tag); // 16
					out.print("\t");
					out.print(meta.playerLimit); // 17
					out.print("\t");
					out.print(meta.thumb == null ? "" : meta.thumb); // 18
					out.println();
				}
			}
		} catch (IOException ex) {
			ex.printStackTrace();
		}
		try {
			Files.copy(Paths.get("stats.tsv"), Paths.get("stats_prev.tsv"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			e.printStackTrace();
		}
		try {
			OutputStream o = new FileOutputStream("stats.tsv");
			try (PrintWriter out = new PrintWriter(new OutputStreamWriter(o, StandardCharsets.UTF_8), false)) {
				o.write(BOM);
				out.println(
						"Type\tStart\tNo\tName\tName2\tFinal\tPlayers\tQualifiedCount\tTime\tQualified\tDisabled\tplayerCoundAdd\tRank\tScore\tFinalScore\tParty\tSquad\tTeam\tTeamScore");
				Match currentMatch = null;
				Collections.sort(rounds);
				for (Round r : rounds) {
					if (!r.isEnabled())
						continue;
					Player p = r.getMe();
					if (p == null)
						continue;
					if (currentMatch == null || !currentMatch.equals(r.match)) {
						currentMatch = r.match;
						// write match line
						out.print("M"); // 0
						out.print("\t");
						out.print(currentMatch.session); // 1
						out.print("\t");
						out.print(f.format(currentMatch.start)); // 2
						out.print("\t");
						out.print(currentMatch.name); // 3
						out.print("\t");
						out.print(currentMatch.ip); // 4
						out.print("\t");
						out.print(currentMatch.pingMS); // 5
						out.print("\t");
						out.print(currentMatch.winStreak); // 6
						out.print("\t");
						out.print(currentMatch.isCustom); // 7
						out.println();
					}
					out.print("r"); // 0
					out.print("\t");
					out.print(f.format(r.start)); // 1
					out.print("\t");
					out.print(r.no); // 2
					out.print("\t");
					out.print(r.name); // 3
					out.print("\t");
					out.print(r.roundName2); // 4
					out.print("\t");
					out.print(r.isFinal()); // 5
					out.print("\t");
					out.print(r.playerCount); // 6
					out.print("\t");
					out.print(r.qualifiedCount); // 7
					out.print("\t");
					if (p.finish != null)
						out.print(r.getTime(p.finish)); // 8
					else if (r.end != null)
						out.print(r.getTime(r.end)); // 8

					out.print("\t");
					out.print(p.isQualified()); // 9
					out.print("\t");
					out.print(r.disableMe); // 10
					out.print("\t");
					out.print(r.playerCountAdd); // 11
					out.print("\t");
					out.print(p.ranking); // 12
					out.print("\t");
					out.print(p.score); // 13
					out.print("\t");
					out.print(p.finalScore); // 14
					out.print("\t");
					out.print(p.partyId); // 15
					out.print("\t");
					out.print(p.squadId); // 16
					out.print("\t");
					out.print(p.teamId); // 17
					out.print("\t");
					if (r.teamScore != null)
						out.print(Arrays.toString(r.teamScore)); // 18
					out.println();
				}
			}
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	static ObjectMapper mapper = new ObjectMapper();

	@SuppressWarnings("unchecked")
	public static CreativeMeta retreiveCreativeInfo(String code, int version, boolean force) {
		try {
			if (code == null)
				return null;
			CreativeMeta meta = Core.creativesMap.get(code);
			if (meta != null && !force)
				return meta;
			URL url = new URL("https://api2.fallguysdb.info/api/creative/" + code + ".json");
			HttpURLConnection con = (HttpURLConnection) url.openConnection();
			con.setRequestProperty("User-Agent", "Chrome");
			con.setRequestProperty("Accept-Encoding", "gzip");
			System.out.println(url);
			InputStream in = con.getInputStream();
			in = new GZIPInputStream(in);
			ByteArrayOutputStream result = new ByteArrayOutputStream();
			byte[] buf = new byte[1024];
			int len;
			while ((len = in.read(buf)) > 0) {
				result.write(buf, 0, len);
			}
			String jsonStr = new String(result.toByteArray(), StandardCharsets.UTF_8);
			Map<String, Object> json = mapper.readValue(jsonStr, Map.class);
			System.out.println(json);

			// generate
			Map<String, Object> data = (Map<String, Object>) json.get("data");
			Map<String, Object> snapshot = (Map<String, Object>) data.get("snapshot");
			Map<String, Object> author = (Map<String, Object>) snapshot.get("author");
			Map<String, Object> names = (Map<String, Object>) author.get("name_per_platform");
			Map<String, Object> version_metadata = (Map<String, Object>) snapshot.get("version_metadata");
			Map<String, Object> config = (Map<String, Object>) version_metadata.get("config");
			Map<String, Object> stats = (Map<String, Object>) snapshot.get("stats");
			if (meta == null)
				meta = new CreativeMeta();
			meta.code = code;
			meta.version = version;
			if (names.size() > 0)
				meta.author = (String) names.values().iterator().next();
			meta.tag = String.join(",", ((List<String>) version_metadata.get("creator_tags")));
			meta.playerLimit = (Integer) version_metadata.get("max_player_count");
			meta.thumb = (String) version_metadata.get("thumb_url");
			meta.title = (String) version_metadata.get("title");
			meta.description = (String) version_metadata.get("description");
			if (config.containsKey("time_limit_seconds")) {
				meta.timeLimitSec = (Integer) config.get("time_limit_seconds");
			}
			meta.playCount = (Integer) stats.get("play_count");
			meta.likes = (Integer) stats.get("likes");
			meta.dislikes = (Integer) stats.get("dislikes");
			Core.addCreativeMeta(meta);
			return meta;
		} catch (IOException e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(null, "Error: " + e.getMessage());
		}
		return null;
	}

	public static AbstractTableModel tableModel = new AbstractTableModel() {
		@Override
		public int getColumnCount() {
			return 14;
		}

		@Override
		public String getColumnName(int column) {
			switch (column) {
			case 0:
				return "Code";
			case 1:
				return "Author";
			case 2:
				return "Title";
			case 3:
				return "CrearTime";
			case 4:
				return "UserTag";
			case 5:
				return "Difficulty";
			case 6:
				return "Review";
			case 7:
				return "Comment";
			case 8:
				return "Description";
			case 9:
				return "TimeLimit";
			case 10:
				return "PlayCount";
			case 11:
				return "LastPlayed";
			case 12:
				return "OfficialTag";
			case 13:
				return "PlayerLimit";
			}
			return "UNKNOWN";
		}

		@Override
		public java.lang.Class<?> getColumnClass(int columnIndex) {
			switch (columnIndex) {
			case 5:
			case 6:
			case 10:
				return Integer.class;
			}
			return String.class;
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			CreativeMeta meta = Core.creativesList.get(rowIndex);
			switch (columnIndex) {
			case 0:
				return meta.code;
			case 1:
				return meta.author;
			case 2:
				return meta.title;
			case 3:
				return meta.clearMS == 0 ? ""
						: String.format("%02d", meta.clearMS / 60000) + ":"
								+ String.format("%02d", meta.clearMS / 1000 % 60) + "."
								+ String.format("%03d", meta.clearMS % 1000);
			case 4:
				return meta.userTag;
			case 5:
				return meta.userDifficulty == 0 ? null : meta.userDifficulty;
			case 6:
				return meta.userScore == 0 ? null : meta.userScore;
			case 7:
				return meta.userComment;
			case 8:
				return meta.description;
			case 9:
				return meta.timeLimitSec == 0 ? null
						: String.format("%02d", meta.timeLimitSec / 60) + ":"
								+ String.format("%02d", meta.timeLimitSec % 60);
			case 10:
				return meta.playCount;
			case 11:
				return meta.lastPlayed == null ? null : Core.dateFormatterMin.format(meta.lastPlayed);
			case 12:
				return meta.tag;
			case 13:
				return meta.playerLimit;
			}
			return "UNKNOWN";
		}

		@Override
		public boolean isCellEditable(int rowIndex, int columnIndex) {
			switch (columnIndex) {
			case 4:
			case 5:
			case 6:
			case 7:
				return true;
			}
			return false;
		}

		@Override
		public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
			CreativeMeta meta = Core.creativesList.get(rowIndex);
			switch (columnIndex) {
			case 4:
				meta.userTag = aValue.toString();
				break;
			case 5:
				meta.userDifficulty = (Integer) aValue;
				break;
			case 6:
				meta.userScore = (Integer) aValue;
				break;
			case 7:
				meta.userComment = aValue.toString();
				break;
			}
			return;
		}

		@Override
		public int getRowCount() {
			return Core.creativesList.size();
		}
	};

	// 2000-01-01 の時刻にする
	static Date normalizedDate(Date d) {
		Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		c.setTime(d);
		c.set(Calendar.YEAR, 2000);
		c.set(Calendar.MONTH, 0);
		c.set(Calendar.DAY_OF_MONTH, 1);
		return c.getTime();
	}

	public static void addMatch(Match m) {
		currentMatch = m;
		synchronized (listLock) {
			// 既にあれば差し替え
			int i = matches.indexOf(m);
			if (i >= 0) {
				matches.remove(i);
				matches.add(i, m);
			} else
				matches.add(m);
			// 直前のマッチのラウンド０だったら除去
			if (matches.size() > 2 && matches.get(matches.size() - 2).rounds.size() == 0)
				matches.remove(matches.size() - 2);
		}
	}

	public static void addRound(Round r) {
		currentRound = r;
		synchronized (listLock) {
			r.match.addRound(r);
			int i = rounds.indexOf(r);
			if (i >= 0) {
				Round o = rounds.remove(i);
				r.playerCountAdd = o.playerCountAdd;
				r.playerCountAddChanged = o.playerCountAddChanged;
				r.disableMe = o.disableMe;
				rounds.add(i, r);
			} else
				rounds.add(r);
		}
	}

	// 条件に合うラウンドを含むマッチ数を数える。
	// ラウンド数ではないことに注意。
	static int getMatchCount(RoundFilter f) {
		if (f == null)
			return matches.size();
		Set<Match> result = new HashSet<>();
		for (Round r : rounds)
			if (f.isEnabled(r))
				result.add(r.match);
		return result.size();
	}

	public static List<Round> filter(RoundFilter f) {
		return filter(f, 0, false);
	}

	public static List<Round> filter(RoundFilter f, int limit, boolean cacheUpdate) {
		List<Round> result = new ArrayList<>();
		int c = 0;
		Match prevMatch = null;
		synchronized (listLock) {
			for (ListIterator<Round> i = rounds.listIterator(rounds.size()); i.hasPrevious();) {
				Round r = i.previous();
				if (f != null && !f.isEnabled(r))
					continue;
				result.add(0, r);
				if (prevMatch == null || prevMatch != r.match) {
					prevMatch = r.match;
					c += 1;
					if (limit > 0 && c >= limit)
						break;
				}
			}
		}
		if (cacheUpdate)
			filtered = result;
		return result;
	}

	public static void updateStats() {
		if (!started)
			return;
		synchronized (listLock) {
			stat.reset();
			for (Round r : filter(filter, limit, true)) {
				if (/*!r.fixed ||*/ !r.isEnabled() || r.getSubstanceQualifiedCount() == 0)
					continue;

				// このラウンドの参加者の結果を反映
				for (Player p : r.byId.values()) {
					if (!"YOU".equals(p.name))
						continue;
					rankingMaker.calcTotalScore(stat, p, r);
				}
			}
			rankingMaker.calcFinish(stat);
		}
	}

	static class PlayerComparator implements Comparator<Player> {
		boolean isHunt;

		PlayerComparator(boolean hunt) {
			isHunt = hunt;
		}

		@Override
		public int compare(Player p1, Player p2) {
			if (p1.ranking > 0 && p2.ranking == 0)
				return -1;
			if (p2.ranking > 0 && p1.ranking == 0)
				return 1;
			if (p1.ranking > 0 && p2.ranking > 0)
				return (int) Math.signum(p1.ranking - p2.ranking);
			if (!isHunt) { // hunt 系の finalScore がバグっていて獲得スコアを出してきてしまう。これでは正確な順位付けができない。
				int v = (int) Math.signum(p2.finalScore - p1.finalScore);
				if (v != 0)
					return v;
			}
			return (int) Math.signum(p2.score - p1.score);
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
	ScheduledExecutorService backgroundService = Executors.newSingleThreadScheduledExecutor();
	Timer survivalScoreTimer;
	Listener listener;

	public FGReader(File log, Listener listener) {
		tailer = new Tailer(log, StandardCharsets.UTF_8, this, 400, false, false, 8192);
		this.listener = listener;
	}

	public void start() {
		thread = new Thread(tailer);
		thread.start();
	}

	public void stop() {
		tailer.stop();
		thread.interrupt();
		backgroundService.shutdown();
	}

	//////////////////////////////////////////////////////////////////
	enum ReadState {
		SHOW_DETECTING, ROUND_DETECTING, MEMBER_DETECTING, RESULT_DETECTING
	}

	ReadState readState = ReadState.SHOW_DETECTING;
	String creativeCode; // ラウンド情報より先に読み取られるのでここで一旦保持してラウンド情報構築時にセットする
	int creativeVersion; // ラウンド情報より先に読み取られるのでここで一旦保持してラウンド情報構築時にセットする
	boolean isCustomShow = false;
	int myObjectId = 0;
	int topObjectId = 0;
	int qualifiedCount = 0;
	int eliminatedCount = 0;
	boolean isFinal = false;

	@Override
	public void handle(String line) {
		try {
			parseLine(line);
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
	}

	static Pattern patternDateDetect = Pattern
			.compile(" (\\d\\d/\\d\\d/\\d\\d\\d\\d \\d\\d:\\d\\d:\\d\\d)[^ ]* LogEOS\\(Info\\)");
	static Pattern patternLaunch = Pattern
			.compile("\\[FGClient.GlobalInitialisation\\] Active Scene is 'Init'");
	static Pattern patternServer = Pattern
			.compile("\\[StateConnectToGame\\] We're connected to the server! Host = ([^:]+)");

	static Pattern patternShowStartNormalShow = Pattern
			.compile("\\[GameStateMachine\\] Replacing FGClient.StateMatchmaking with FGClient.StateConnectToGame");
	static Pattern patternShowStartCustomShow = Pattern
			.compile("\\[GameStateMachine\\] Replacing FGClient.StatePrivateLobby with FGClient.StateConnectToGame");

	static Pattern patternShowName = Pattern.compile("\\[HandleSuccessfulLogin\\] Selected show is ([^\\s]+)");
	//	static Pattern patternShow = Pattern
	//			.compile("\\[HandleSuccessfulLogin\\] Selected show is ([^\\s]+)");
	//static Pattern patternMatchStart = Pattern.compile("\\[StateMatchmaking\\] Begin ");

	static Pattern patternRoundName = Pattern.compile(
			"\\[RoundLoader\\] LoadGameLevelSceneASync COMPLETE for scene ([^\\s]+) on frame (\\d+)");
	static Pattern patternLoadedRound = Pattern
			.compile("\\[StateGameLoading\\] Finished loading game level, assumed to be ([^.]+)\\.");
	static Pattern patternRoundSeed = Pattern
			.compile("\\[GameManager\\] Creating with random seed = ([-\\d]+)");

	static Pattern patternLocalPlayerId = Pattern
			.compile(
					"\\[ClientGameManager\\] Handling bootstrap for local player FallGuy \\[(\\d+)\\] \\(FG.Common.MPGNetObject\\), playerID = (\\d+), squadID = (\\d+)");
	static Pattern patternPlayerObjectId = Pattern.compile(
			"\\[ClientGameManager\\] Handling bootstrap for [^ ]+ player FallGuy \\[(\\d+)\\].+, playerID = (\\d+)");
	static Pattern patternPlayerSpawned = Pattern.compile(
			"\\[CameraDirector\\] Adding Spectator target (.+) \\((.+)\\) with Party ID: (\\d*)  Squad ID: (\\d+) and playerID: (\\d+)");
	//static Pattern patternPlayerSpawnFinish = Pattern.compile("\\[ClientGameManager\\] Finalising spawn for player FallGuy \\[(\\d+)\\] (.+) \\((.+)\\) ");
	static Pattern patternPlayerActive = Pattern.compile(
			"ClientGameManager::HandleServerPlayerProgress PlayerId=(\\d+) is succeeded=(.+)");
	static Pattern patternPlayerUnspawned = Pattern
			.compile("\\[ClientGameManager\\] Handling unspawn for player (FallGuy \\[)?(\\d+)\\]?");

	static Pattern patternScoreUpdated = Pattern.compile("Player (\\d+) score = (\\d+)");
	static Pattern patternTeamScoreUpdated = Pattern.compile("Team (\\d+) score = (\\d+)");
	static Pattern patternPlayerResult = Pattern.compile(
			"ClientGameManager::HandleServerPlayerProgress PlayerId=(\\d+) is succeeded=([^\\s]+)");

	static Pattern patternPlayerResult2 = Pattern.compile(
			"-playerId:(\\d+) points:(\\d+) isfinal:([^\\s]+) name:");

	static Pattern patternCreativeCode = Pattern.compile("\\[RoundLoader\\] Load UGC via share code: ([\\d-]+):(\\d+)");

	static DateFormat dateLocal = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
	static DateFormat f = new SimpleDateFormat("HH:mm:ss.SSS");
	static {
		f.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	static Date getTime(String line) {
		try {
			Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
			Calendar parsed = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
			parsed.setTime(f.parse(line.substring(0, 12)));
			c.set(Calendar.YEAR, Core.currentYear);
			c.set(Calendar.MONTH, Core.currentMonth);
			c.set(Calendar.DAY_OF_MONTH, Core.currentUTCDate);
			c.set(Calendar.HOUR_OF_DAY, parsed.get(Calendar.HOUR_OF_DAY));
			c.set(Calendar.MINUTE, parsed.get(Calendar.MINUTE));
			c.set(Calendar.SECOND, parsed.get(Calendar.SECOND));
			c.set(Calendar.MILLISECOND, parsed.get(Calendar.MILLISECOND));
			return c.getTime();
		} catch (ParseException e) {
			//e.printStackTrace();
		}
		return new Date();
	}

	static class IPChecker extends TimerTask {
		final Match match;
		final Listener listener;

		IPChecker(Match match, Listener listener) {
			this.match = match;
			this.listener = listener;
		}

		@Override
		public void run() {
			// ping check
			try {
				InetAddress address = InetAddress.getByName(match.ip);
				long now = System.currentTimeMillis();
				boolean res = address.isReachable(3000);
				match.pingMS = System.currentTimeMillis() - now;
				System.out.println("PING " + res + " " + match.pingMS);
				Map<String, String> server = Core.servers.get(match.ip);
				if (server == null) {
					ObjectMapper mapper = new ObjectMapper();
					JsonNode root = mapper.readTree(new URL("http://ip-api.com/json/" + match.ip));
					server = new HashMap<String, String>();
					server.put("country", root.get("country").asText());
					server.put("regionName", root.get("regionName").asText());
					server.put("city", root.get("city").asText());
					server.put("timezone", root.get("timezone").asText());
					Core.servers.put(match.ip, server);
				}
				if (Core.currentMatch.start.getTime() > System.currentTimeMillis() - 30 * 60 * 1000)
					listener.showUpdated();
				System.err.println(match.ip + " " + match.pingMS + " " + server.get("timezone") + " "
						+ server.get("city"));
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void parseLine(String line) {
		Round r = Core.currentRound;
		Matcher m = patternLaunch.matcher(line);
		if (m.find()) {
			Core.currentSession = getTime(line).getTime();
			return;
		}
		m = patternDateDetect.matcher(line);
		if (m.find()) {
			try {
				Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
				c.setTime(dateLocal.parse(m.group(1)));
				Core.currentYear = c.get(Calendar.YEAR);
				Core.currentMonth = c.get(Calendar.MONTH);
				Core.currentUTCDate = c.get(Calendar.DAY_OF_MONTH);
			} catch (ParseException e) {
				//e.printStackTrace();
			}
			return;
		}
		/*
		if (line.contains("[UserInfo] Player Name:")) {
			String[] sp = line.split("Player Name: ", 2);
			Core.myNameFull = sp[1];
		}
		*/
		m = patternServer.matcher(line);
		if (m.find()) {
			String showName = "_";
			String ip = m.group(1);
			Match match = new Match(Core.currentSession, showName, getTime(line), ip, isCustomShow);
			Core.addMatch(match);
			System.out.println("DETECT SHOW STARTING " + showName);
			readState = ReadState.ROUND_DETECTING;

			if (match.pingMS == 0) {
				Core.currentServerIp = ip;
				backgroundService.execute(new IPChecker(match, listener));
			}
			if (Core.currentMatch.start.getTime() > System.currentTimeMillis() - 30 * 60 * 1000)
				listener.showUpdated();
			return;
		}
		m = patternCreativeCode.matcher(line);
		if (m.find()) {
			creativeCode = m.group(1);
			creativeVersion = Integer.parseUnsignedInt(m.group(2));
			System.out.println(creativeCode + " Version:" + creativeVersion);
		}
		m = patternLocalPlayerId.matcher(line);
		if (m.find()) {
			r.myPlayerId = Integer.parseUnsignedInt(m.group(2));
		}
		switch (readState) {
		case SHOW_DETECTING: // start show or round detection
		case ROUND_DETECTING: // start round detection
			m = patternShowStartNormalShow.matcher(line);
			if (m.find()) {
				isCustomShow = false;
				return;
			}
			m = patternShowStartCustomShow.matcher(line);
			if (m.find()) {
				isCustomShow = true;
				return;
			}
			m = patternShowName.matcher(line);
			if (m.find()) {
				String showName = m.group(1);
				Core.currentMatch.name = showName;
				if (Core.currentMatch.start.getTime() > System.currentTimeMillis() - 30 * 60 * 1000)
					System.out.println("SHOW NAME UPDATED: " + showName);
				listener.showUpdated();
				return;
			}
			if (line.contains("isFinalRound=")) {
				isFinal = line.contains("isFinalRound=True");
				return;
			}
			m = patternRoundName.matcher(line);
			if (m.find()) {
				String roundName = m.group(1);
				//long frame = Long.parseUnsignedLong(m.group(2)); // FIXME: round id のほうが適切
				Core.addRound(new Round(roundName, Core.currentMatch.rounds.size(), getTime(line), isFinal,
						Core.currentMatch));
				r = Core.currentRound;
				System.out.println("DETECT STARTING " + roundName);
				//readState = ReadState.MEMBER_DETECTING;
				r.creativeCode = creativeCode;
				r.creativeVersion = creativeVersion;
				if (r.creativeCode != null) {
					CreativeMeta meta = Core.retreiveCreativeInfo(r.creativeCode, r.creativeVersion, false);
					if (meta != null) {
						if (meta.played == null)
							meta.played = getTime(line);
						meta.lastPlayed = getTime(line);
						if (r.start.getTime() > System.currentTimeMillis() - 2 * 60 * 1000)
							SwingUtilities.invokeLater(() -> Core.tableModel.fireTableDataChanged());
					}
				}
				creativeCode = null;
				creativeVersion = 0;
				return;
			}
			m = patternLoadedRound.matcher(line);
			if (m.find()) {
				String roundName2 = m.group(1);
				r.roundName2 = roundName2;
				System.out.println("DETECT STARTING " + roundName2);
				readState = ReadState.MEMBER_DETECTING;
				return;
			}
			return;
		case MEMBER_DETECTING: // join detection
			// 本来 playerId, name が先に検出されるべきだが、playerId, objectId が先に出力されうるためどちらが先でも対応できるようにする。
			m = patternPlayerObjectId.matcher(line);
			if (m.find()) {
				int playerObjectId = Integer.parseUnsignedInt(m.group(1));
				int playerId = Integer.parseUnsignedInt(m.group(2));
				Player p = r.byId.get(playerId);
				if (p == null) {
					p = new Player(playerId);
					r.add(p);
				}
				p.objectId = playerObjectId;
				// System.out.println("playerId=" + playerId + " objectId=" + playerObjectId);
				return;
			}
			m = patternPlayerSpawned.matcher(line);
			if (m.find()) {
				String name = m.group(1);
				String platform = m.group(2);
				int partyId = m.group(3).length() == 0 ? 0 : Integer.parseUnsignedInt(m.group(3)); // 空文字列のことあり
				int squadId = Integer.parseUnsignedInt(m.group(4));
				int playerId = Integer.parseUnsignedInt(m.group(5));
				// win...xxx のような末尾３文字だけになった
				String playerName = platform + name;

				Player p = r.byId.get(playerId);
				if (p == null) {
					p = new Player(playerId);
				}
				p.partyId = partyId;
				p.squadId = squadId;
				p.name = playerName + p.id;
				p.platform = platform;
				r.add(p);
				if (r.myPlayerId == p.id) {
					p.name = "YOU";
					r.add(p);
					//Core.myName = p.name;
				}

				System.out.println(r.byId.size() + " Player " + playerName + " (id=" + playerId
						+ " squadId=" + squadId + ") spwaned.");
				if (Core.currentMatch.start.getTime() > System.currentTimeMillis() - 30 * 60 * 1000)
					listener.roundUpdated();
				// 現在の自分の objectId 更新
				// if (Core.myName.equals(p.name))
				if (r.myPlayerId == p.id)
					myObjectId = p.objectId;
				return;
			}
			// こちらで取れる名前は旧名称だった…
			/* この行での名前出力がなくなっていた
			m = patternPlayerSpawnFinish.matcher(line);
			if (m.find()) {
				int playerObjectId = Integer.parseUnsignedInt(m.group(1));
				String name = m.group(2);
				Player p = r.getByObjectId(playerObjectId);
				if (p != null) {
					if (name.length() == 5) // 名前が短いと'a...b'のように前後１文字に短縮されている。元の名前の末尾３文字を活かす
						p.name = name.substring(0, 4) + p.name.substring(p.name.length() - 3);
					else
						p.name = name;
					if (r.myPlayerId == p.id)
						Core.myName = p.name;
				}
				r.add(p);
				return;
			}
			*/
			// player spwan succeeded
			m = patternPlayerActive.matcher(line);
			if (m.find()) {
				if ("True".equals(m.group(2)))
					return;
				int playerId = Integer.parseUnsignedInt(m.group(1));
				Player player = r.byId.get(playerId);
				if (player == null)
					return;
				System.out.println("missing player " + player);
				player.qualified = false;
				//player.finish = getTime(line);
				return;
			}

			if (line.contains("[StateGameLoading] Starting the game.")) {
				if (Core.currentMatch.start.getTime() > System.currentTimeMillis() - 30 * 60 * 1000)
					listener.roundStarted();
				return;
			}
			if (line.contains("[GameSession] Changing state from Countdown to Playing")) {
				// start を書き換える前のエントリを除去
				synchronized (Core.listLock) {
					Core.rounds.remove(r);
					Core.currentMatch.rounds.remove(r);
				}
				r.start = getTime(line);
				Core.addRound(r); // 再add
				topObjectId = 0;
				qualifiedCount = eliminatedCount = 0; // reset
				readState = ReadState.RESULT_DETECTING;
				if (Core.currentMatch.start.getTime() > System.currentTimeMillis() - 30 * 60 * 1000) {
					listener.roundStarted();
					if (r.getDef().type == RoundType.SURVIVAL) {
						if (survivalScoreTimer != null)
							survivalScoreTimer.cancel();
						survivalScoreTimer = new Timer();
						survivalScoreTimer.scheduleAtFixedRate(new TimerTask() {
							@Override
							public void run() {
								for (Player p : Core.currentRound.byId.values()) {
									if (p.qualified == null)
										p.score += 1;
								}
								listener.roundUpdated();
							}
						}, 1000, 1000);
					}
				}
				return;
			}
			if (line.contains("[StateMainMenu] Creating or joining lobby")
					|| line.contains("[StateMatchmaking] Begin matchmaking")) {
				System.out.println("DETECT BACK TO LOBBY");
				Core.rounds.remove(Core.rounds.size() - 1); // delete current round
				readState = ReadState.SHOW_DETECTING;
				return;
			}
			return;
		case RESULT_DETECTING: // result detection
			// score update duaring round
			m = patternScoreUpdated.matcher(line);
			if (m.find()) {
				int playerObjectId = Integer.parseUnsignedInt(m.group(1));
				int score = Integer.parseUnsignedInt(m.group(2));
				Player player = r.getByObjectId(playerObjectId);
				if (player != null) {
					if (player.score != score) {
						System.out.println(player + " score " + player.score + " -> " + score);
						player.score = score;
						if (Core.currentMatch.start.getTime() > System.currentTimeMillis() - 30 * 60 * 1000)
							listener.roundUpdated();
					}
				}
				return;
			}
			m = patternTeamScoreUpdated.matcher(line);
			if (m.find()) {
				int teamCount = r.getDef().teamCount;
				if (teamCount < 2)
					return;
				int teamId = Integer.parseUnsignedInt(m.group(1));
				int score = Integer.parseUnsignedInt(m.group(2));
				if (r.teamScore == null)
					r.teamScore = new int[teamCount];
				r.teamScore[teamId] = score;
				if (Core.currentMatch.start.getTime() > System.currentTimeMillis() - 30 * 60 * 1000)
					listener.roundUpdated();
				return;
			}
			// finish time handling
			m = patternPlayerUnspawned.matcher(line);
			if (m.find()) {
				int objectId = Integer.parseUnsignedInt(m.group(2));
				Player player = r.getByObjectId(objectId);
				if (player == null)
					return;
				player.finish = getTime(line);
				if (topObjectId == 0) {
					topObjectId = objectId;
					r.topFinish = player.finish;
				}
				if (Core.currentMatch.start.getTime() > System.currentTimeMillis() - 30 * 60 * 1000)
					listener.roundUpdated();
				return;
			}

			// qualified / eliminated
			m = patternPlayerResult.matcher(line);
			if (m.find()) {
				int playerId = Integer.parseUnsignedInt(m.group(1));
				boolean succeeded = "True".equals(m.group(2));
				Player player = r.byId.get(playerId);
				if (!succeeded)
					System.out.print("Eliminated for " + playerId + " ");
				if (player != null) {
					player.qualified = succeeded;
					if (succeeded) {
						// スコア出力がない場合の仮スコア付
						switch (RoundDef.get(r.name).type) {
						case RACE:
							player.score += r.byId.size() - qualifiedCount;
							break;
						case HUNT_RACE:
						case HUNT_SURVIVE:
						case SURVIVAL:
						case TEAM:
							if (player.score == 0)
								player.score = 1;
							break;
						}
						qualifiedCount += 1;
						r.qualifiedCount += 1;
						player.ranking = qualifiedCount;
						System.out.println("Qualified " + player + " rank=" + player.ranking + " " + player.score);
						if ("YOU".equals(player.name)) {
							if (r.creativeCode != null) {
								CreativeMeta meta = Core.retreiveCreativeInfo(r.creativeCode, r.creativeVersion, false);
								long time = r.getTime(player.finish);
								if (meta != null && (meta.clearMS == 0 || meta.clearMS > time)) {
									meta.clearMS = time;
									if (r.start.getTime() > System.currentTimeMillis() - 2 * 60 * 1000)
										SwingUtilities.invokeLater(() -> Core.tableModel.fireTableDataChanged());
								}
							}
						}
					} else {
						if (topObjectId == player.objectId) {
							topObjectId = 0; // 切断でも Handling unspawn が出るのでこれを無視して先頭ゴールのみ検出するため
							r.topFinish = null;
						}
						player.ranking = r.byId.size() - eliminatedCount;
						eliminatedCount += 1;
						System.out.println(player);
					}
					if (Core.currentMatch.start.getTime() > System.currentTimeMillis() - 30 * 60 * 1000)
						listener.roundUpdated();
				}
				return;
			}
			// score log
			// round over より後に出力されている。
			m = patternPlayerResult2.matcher(line);
			if (m.find()) {
				int playerId = Integer.parseUnsignedInt(m.group(1));
				int finalScore = Integer.parseUnsignedInt(m.group(2));
				boolean isFinal = "True".equals(m.group(3));
				Player player = r.byId.get(playerId);
				System.out.println(
						"Result for " + playerId + " score=" + finalScore + " isFinal=" + isFinal + " " + player);
				if (player != null) {
					if (player.squadId > 0) { // 最後の squad 情報がバグで毎回出力されている
						player.finalScore = finalScore;
					}
				}
				return;
			}
			// round end
			//if (text.contains("[ClientGameManager] Server notifying that the round is over.")
			if (line.contains("[GameSession] Changing state from Playing to GameOver")) {
				r.end = getTime(line);
				if (survivalScoreTimer != null) {
					survivalScoreTimer.cancel();
					survivalScoreTimer.purge();
					survivalScoreTimer = null;
				}
				return;
			}
			if (line.contains(
					"[GameStateMachine] Replacing FGClient.StateGameInProgress with FGClient.StateQualificationScreen")
					|| line.contains(
							"[GameStateMachine] Replacing FGClient.StateGameInProgress with FGClient.StateVictoryScreen")) {
				System.out.println("DETECT END GAME");
				r.fixed = true;
				// FIXME: teamId 相当が出力されないので誰がどのチームか判定できない。
				// 仕方ないので勝敗からチームを推測する。これだと２チーム戦しか対応できない。
				if (r.teamScore != null) {
					for (Player p : r.byId.values()) {
						if (r.teamScore[0] >= r.teamScore[1])
							p.teamId = Boolean.TRUE == p.qualified ? 0 : 1;
						else
							p.teamId = Boolean.TRUE == p.qualified ? 1 : 0;
					}
				}
				Core.currentMatch.end = getTime(line);
				// 優勝画面に行ったらそのラウンドをファイナル扱いとする
				// final マークがつかないファイナルや、通常ステージで一人生き残り優勝のケースを補填するためだが
				// 通常ステージでゲーム終了時それをファイナルステージとみなすべきかはスコアリング上微妙ではある。
				if (line.contains(
						"[GameStateMachine] Replacing FGClient.StateGameInProgress with FGClient.StateVictoryScreen")) {
					r.isFinal = true;
					Core.currentMatch.finished(getTime(line));
				}
				if (Core.currentMatch.start.getTime() > System.currentTimeMillis() - 30 * 60 * 1000)
					listener.roundDone();
				readState = ReadState.ROUND_DETECTING;
				return;
			}
			if (line.contains("== [CompletedEpisodeDto] ==")) {
				// 獲得 kudos 他はこの後に続く、決勝完了前に吐くこともあるのでステージ完了ではない。
				return;
			}
			if (line.contains(
					"[GameStateMachine] Replacing FGClient.StatePrivateLobby with FGClient.StateMainMenu")
					|| line.contains("[StateMainMenu] Creating or joining lobby")
					|| line.contains("[StateMainMenu] Loading scene MainMenu")
					|| line.contains("[StateMatchmaking] Begin matchmaking")
					|| line.contains("Changing local player state to: SpectatingEliminated")
					|| line.contains("[GlobalGameStateClient] SwitchToDisconnectingState")) {
				if (survivalScoreTimer != null) {
					survivalScoreTimer.cancel();
					survivalScoreTimer.purge();
					survivalScoreTimer = null;
				}
				readState = ReadState.SHOW_DETECTING;
				Core.currentMatch.finished(getTime(line));
				return;
			}
			return;
		}
	}
}

// UI
public class FallGuysRecord extends JFrame implements FGReader.Listener {
	static int FONT_SIZE_BASE;
	static int FONT_SIZE_RANK;
	static int FONT_SIZE_DETAIL;

	static ServerSocketMutex mutex = new ServerSocketMutex(29878);
	static FallGuysRecord frame;
	static CreativesWindow creativesWindow;
	static FGReader reader;
	static String monospacedFontFamily = "MS Gothic";
	static String fontFamily = "Meiryo UI";

	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		if (!mutex.tryLock()) {
			System.exit(0);
		}
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
		Core.LANG = v == null ? Locale.getDefault() : new Locale(v);
		Core.RES = ResourceBundle.getBundle("res", Core.LANG, new UTF8Control());

		v = prop.getProperty("FONT_SIZE_BASE");
		FONT_SIZE_BASE = v == null ? 12 : Integer.parseInt(v, 10);
		v = prop.getProperty("FONT_SIZE_RANK");
		FONT_SIZE_RANK = v == null ? 16 : Integer.parseInt(v, 10);
		v = prop.getProperty("FONT_SIZE_DETAIL");
		FONT_SIZE_DETAIL = v == null ? 16 : Integer.parseInt(v, 10);

		System.err.println("FONT_SIZE_BASE=" + FONT_SIZE_BASE);
		System.err.println("FONT_SIZE_RANK=" + FONT_SIZE_RANK);
		System.err.println("FONT_SIZE_DETAIL=" + FONT_SIZE_DETAIL);
		Rectangle winRect = new Rectangle(10, 10, 1280, 628);
		Rectangle creativesWinRect = new Rectangle(10, 10, 1280, 628);
		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream("state.dat"))) {
			winRect = (Rectangle) in.readObject();
			Core.servers = (Map<String, Map<String, String>>) in.readObject();
			creativesWinRect = (Rectangle) in.readObject();
		} catch (IOException ex) {
		}

		Core.load();

		frame = new FallGuysRecord();
		frame.setResizable(true);
		frame.setBounds(winRect);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		frame.readLog();

		frame.setVisible(true);
		Core.started = true;
		// ad-hoc show initial stats
		// ラウンド終了検出で更新されるがそれだけだと起動時ログがないときの初期表示がされないのでとりあえず
		Core.updateStats();
		frame.updateRounds();
		frame.displayStats();

		reader.start();

		creativesWindow = new CreativesWindow();
		creativesWindow.setResizable(true);
		creativesWindow.setBounds(creativesWinRect);
		creativesWindow.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		creativesWindow.setVisible(true);
		creativesWindow.loadTableStat();
	}

	void showCreativesWindow() {
		creativesWindow.setVisible(true);
	}

	JLabel pingLabel;
	JTextPane statsArea;
	JList<Match> matchSel;
	JList<Round> roundsSel;
	JTextPane roundDetailArea;
	JComboBox<RoundFilter> filterSel;
	JComboBox<Integer> limitSel;
	JLabel rankingDescLabel;
	boolean ignoreSelEvent;

	static final int LINE1_Y = 10;
	static final int COL1_X = 10;

	FallGuysRecord() {
		setTitle("Fall Guys Record");
		SpringLayout l = new SpringLayout();
		Container p = getContentPane();
		p.setLayout(l);

		JLabel label = new JLabel(Core.getRes("rankingLabel"));
		label.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE + 2));
		l.putConstraint(SpringLayout.WEST, label, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(200, 20);
		p.add(label);
		JLabel statsLabel = label;

		final int COL2_X = COL1_X + FONT_SIZE_RANK * 18 + 10;
		final int COL3_X = COL2_X + 130;
		final int COL4_X = COL3_X + 160;

		label = new JLabel(Core.getRes("matchList"));
		label.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE + 2));
		l.putConstraint(SpringLayout.WEST, label, COL2_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);
		label = new JLabel(Core.getRes("roundList"));
		label.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE + 2));
		l.putConstraint(SpringLayout.WEST, label, COL3_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);
		label = new JLabel(Core.getRes("roundDetails"));
		label.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE + 2));
		l.putConstraint(SpringLayout.WEST, label, COL4_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);

		label = new JLabel("Ver 1.1.0");
		label.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.EAST, label, -8, SpringLayout.EAST, p);
		l.putConstraint(SpringLayout.SOUTH, label, -8, SpringLayout.SOUTH, p);
		p.add(label);

		// under
		pingLabel = new JLabel("");
		pingLabel.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_RANK));
		l.putConstraint(SpringLayout.WEST, pingLabel, 10, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.SOUTH, pingLabel, -4, SpringLayout.SOUTH, p);
		pingLabel.setPreferredSize(new Dimension(FONT_SIZE_RANK * 60, FONT_SIZE_RANK + 10));
		p.add(pingLabel);

		// styles
		StyledDocument rdoc = new DefaultStyledDocument();
		Style def = rdoc.getStyle(StyleContext.DEFAULT_STYLE);
		Style s = rdoc.addStyle("bold", def);
		StyleConstants.setBold(s, true);

		StyledDocument doc = new DefaultStyledDocument();
		def = doc.getStyle(StyleContext.DEFAULT_STYLE);
		s = doc.addStyle("bold", def);
		StyleConstants.setBold(s, true);
		s = doc.addStyle("underscore", def);
		StyleConstants.setUnderline(s, true);
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

		JScrollPane scroller;
		JComboBox<RankingMaker> rankingMakerSel = new JComboBox<RankingMaker>();
		rankingMakerSel.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, rankingMakerSel, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.SOUTH, rankingMakerSel, -10, SpringLayout.NORTH, pingLabel);
		rankingMakerSel.setPreferredSize(new Dimension(150, FONT_SIZE_BASE + 8));
		p.add(rankingMakerSel);
		rankingMakerSel.addItem(new RankingMaker());
		rankingMakerSel.addItem(new SquadsRankingMaker());
		rankingMakerSel.addItem(new FallBallRankingMaker());
		rankingMakerSel.addItem(new OneOnOneRankingMaker());
		rankingMakerSel.addItem(new CandyRankingMaker());
		rankingMakerSel.addItemListener(ev -> {
			Core.rankingMaker = (RankingMaker) rankingMakerSel.getSelectedItem();
			rankingDescLabel.setText(Core.rankingMaker.getDesc());
			Core.updateStats();
			displayStats();
		});
		rankingDescLabel = new JLabel(Core.rankingMaker.getDesc());
		rankingDescLabel.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_BASE + 2));
		l.putConstraint(SpringLayout.WEST, rankingDescLabel, 10, SpringLayout.EAST, rankingMakerSel);
		l.putConstraint(SpringLayout.SOUTH, rankingDescLabel, -10, SpringLayout.NORTH, pingLabel);
		rankingDescLabel.setPreferredSize(new Dimension(800, FONT_SIZE_BASE + 8));
		p.add(rankingDescLabel);

		statsArea = new NoWrapJTextPane(rdoc);
		statsArea.setFont(new Font(monospacedFontFamily, Font.PLAIN, FONT_SIZE_RANK));
		statsArea.setMargin(new Insets(8, 8, 8, 8));
		p.add(scroller = new JScrollPane(statsArea));
		l.putConstraint(SpringLayout.WEST, scroller, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.EAST, scroller, COL2_X - 10, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, statsLabel);
		l.putConstraint(SpringLayout.HEIGHT, scroller, 150, SpringLayout.NORTH, p);

		JButton showCreativesButton = new JButton(Core.getRes("ShowCreatives"));
		showCreativesButton.setFont(new Font(monospacedFontFamily, Font.BOLD, FONT_SIZE_BASE));
		showCreativesButton.setSize(80, 18);
		p.add(showCreativesButton);
		l.putConstraint(SpringLayout.WEST, showCreativesButton, COL1_X, SpringLayout.WEST, p);
		//l.putConstraint(SpringLayout.EAST, showCreativesButton, -10, SpringLayout.EAST, p);
		l.putConstraint(SpringLayout.NORTH, showCreativesButton, -150, SpringLayout.SOUTH, p);
		//l.putConstraint(SpringLayout.SOUTH, showCreativesButton, -60, SpringLayout.NORTH, rankingMakerSel);
		showCreativesButton.addActionListener(ev -> {
			showCreativesWindow();
		});

		filterSel = new JComboBox<RoundFilter>();
		filterSel.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, filterSel, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, filterSel, -120, SpringLayout.SOUTH, p);
		filterSel.setSize(95, 20);
		filterSel.addItem(new CurrentSessionRoundFilter());
		filterSel.addItem(new AllRoundFilter());
		filterSel.addItemListener(ev -> {
			Core.filter = (RoundFilter) filterSel.getSelectedItem();
			Core.updateStats();
			updateMatches();
			updateRounds();
		});
		p.add(filterSel);

		limitSel = new JComboBox<Integer>();
		limitSel.setFont(new Font(fontFamily, Font.BOLD, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, limitSel, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, limitSel, -90, SpringLayout.SOUTH, p);
		limitSel.setSize(44, 20);
		limitSel.addItem(0);
		limitSel.addItem(10);
		limitSel.addItem(20);
		limitSel.addItem(50);
		limitSel.addItem(100);
		limitSel.addItem(500);
		limitSel.setSelectedItem(100);
		limitSel.addItemListener(ev -> {
			Core.limit = (int) limitSel.getSelectedItem();
			Core.updateStats();
			updateMatches();
			updateRounds();
		});
		p.add(limitSel);
		label = new JLabel(Core.getRes("limitMatch"));
		label.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_BASE));
		l.putConstraint(SpringLayout.WEST, label, 6, SpringLayout.EAST, limitSel);
		l.putConstraint(SpringLayout.NORTH, label, 2, SpringLayout.NORTH, limitSel);
		label.setSize(120, 20);
		p.add(label);

		matchSel = new JList<Match>(new FastListModel<>());
		matchSel.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_BASE + 4));
		p.add(scroller = new JScrollPane(matchSel));
		l.putConstraint(SpringLayout.WEST, scroller, COL2_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, statsLabel);
		l.putConstraint(SpringLayout.SOUTH, scroller, -40, SpringLayout.NORTH, rankingMakerSel);
		scroller.setPreferredSize(new Dimension(120, 0));
		matchSel.addListSelectionListener((ev) -> {
			if (ev.getValueIsAdjusting()) {
				// The user is still manipulating the selection.
				return;
			}
			matchSelected(getSelectedMatch());
		});

		roundsSel = new JList<Round>(new FastListModel<>());
		roundsSel.setFont(new Font(fontFamily, Font.PLAIN, FONT_SIZE_BASE + 4));
		p.add(scroller = new JScrollPane(roundsSel));
		l.putConstraint(SpringLayout.WEST, scroller, COL3_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, statsLabel);
		l.putConstraint(SpringLayout.SOUTH, scroller, -40, SpringLayout.NORTH, rankingMakerSel);
		scroller.setPreferredSize(new Dimension(150, 0));
		roundsSel.addListSelectionListener((ev) -> {
			if (ev.getValueIsAdjusting()) {
				// The user is still manipulating the selection.
				return;
			}
			roundSelected(getSelectedRound());
		});

		roundDetailArea = new NoWrapJTextPane(doc);
		roundDetailArea.setFont(new Font(monospacedFontFamily, Font.PLAIN, FONT_SIZE_DETAIL));
		roundDetailArea.setMargin(new Insets(8, 8, 8, 8));
		p.add(scroller = new JScrollPane(roundDetailArea));
		l.putConstraint(SpringLayout.WEST, scroller, COL4_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.EAST, scroller, -10, SpringLayout.EAST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 8, SpringLayout.SOUTH, statsLabel);
		l.putConstraint(SpringLayout.SOUTH, scroller, -40, SpringLayout.NORTH, rankingMakerSel);

		this.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				reader.stop();
				try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream("state.dat"))) {
					out.writeObject(frame.getBounds());
					out.writeObject(Core.servers);
					out.writeObject(creativesWindow.getBounds());
				} catch (IOException ex) {
					ex.printStackTrace();
				}
				creativesWindow.saveTableStat();
				Core.save();
				// log connected servers statistics
				Map<String, Integer> connected = new HashMap<String, Integer>();
				for (Match m : Core.matches) {
					Map<String, String> server = Core.servers.get(m.ip);
					if (server == null)
						continue;
					Integer count = connected.get(server.get("city"));
					if (count == null)
						count = 0;
					connected.put(server.get("city"), count + 1);
				}
				/*
				for (String city : connected.keySet()) {
					System.err.println(city + "\t" + connected.get(city));
				}
				for (Match m : Core.matches) {
					System.err.println("****** " + m.name);
					for (Round r : m.rounds) {
						System.err.println(r.name + "\t" + r.roundName2);
					}
				}
				*/
			}
		});
	}

	public void readLog() {
		// start log read
		reader = new FGReader(
				new File(FileUtils.getUserDirectory(), "AppData/LocalLow/Mediatonic/FallGuys_client/Player.log"), this);
		readLogInternal(
				new File(FileUtils.getUserDirectory(), "AppData/LocalLow/Mediatonic/FallGuys_client/Player-prev.log"));
	}

	void readLogInternal(File log) {
		try (BufferedReader in = new BufferedReader(
				new InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				reader.handle(line);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	void updateMatches() {
		int prevSelectedIndex = matchSel.getSelectedIndex();
		FastListModel<Match> model = (FastListModel<Match>) matchSel.getModel();
		model.clear();
		model.add(new Match(0, "ALL", null, null, false));
		synchronized (Core.listLock) {
			//for (Match m : Core.matches) {
			for (Match m : Core.filtered.stream().map(o -> o.match).distinct().collect(Collectors.toList())) {
				model.add(m);
			}
			model.fireAdded();
			matchSel.setSelectedIndex(prevSelectedIndex <= 0 ? 0 : model.getSize() - 1);
			matchSel.ensureIndexIsVisible(matchSel.getSelectedIndex());
		}
		displayFooter();
	}

	void updateRounds() {
		FastListModel<Round> model = (FastListModel<Round>) roundsSel.getModel();
		model.clear();
		synchronized (Core.listLock) {
			Match m = getSelectedMatch();
			for (Round r : m == null ? Core.filtered : m.rounds) {
				model.add(r);
			}
			model.fireAdded();
			roundsSel.setSelectedIndex(model.size() - 1);
			roundsSel.ensureIndexIsVisible(roundsSel.getSelectedIndex());
			displayStats();
		}
	}

	void matchSelected(Match m) {
		FastListModel<Round> model = (FastListModel<Round>) roundsSel.getModel();
		model.clear();
		synchronized (Core.listLock) {
			for (Round r : m == null ? Core.filtered : m.rounds) {
				model.add(r);
			}
			model.fireAdded();
		}
		roundsSel.setSelectedIndex(model.size() - 1);
		roundsSel.ensureIndexIsVisible(roundsSel.getSelectedIndex());
		displayFooter();
	}

	private void appendToStats(String str, String style) {
		style = style == null ? StyleContext.DEFAULT_STYLE : style;
		StyledDocument doc = statsArea.getStyledDocument();
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
		if (r == null)
			return;
		refreshRoundDetail(r);
	}

	@Override
	public void showUpdated() {
		if (!Core.started)
			return;
		SwingUtilities.invokeLater(() -> {
			updateMatches();
		});
	}

	@Override
	public void roundStarted() {
		if (!Core.started)
			return;
		SwingUtilities.invokeLater(() -> {
			Core.updateStats();
			updateMatches();
			updateRounds();
		});
	}

	@Override
	public void roundUpdated() {
		if (!Core.started)
			return;
		SwingUtilities.invokeLater(() -> {
			if (Core.currentRound == getSelectedRound())
				refreshRoundDetail(getSelectedRound());
		});
	}

	@Override
	public void roundDone() {
		if (!Core.started)
			return;
		SwingUtilities.invokeLater(() -> {
			Core.updateStats();
			updateRounds();
		});
	}

	Match getSelectedMatch() {
		Match m = matchSel.getSelectedValue();
		if (m == null || "ALL".equals(m.name))
			return null;
		return m;
	}

	Round getSelectedRound() {
		return roundsSel.getSelectedValue();
	}

	void refreshRoundDetail(Round r) {
		roundDetailArea.setText("");
		if (r == null) {
			return;
		}
		CreativeMeta meta = Core.retreiveCreativeInfo(r.creativeCode, r.creativeVersion, false);
		appendToRoundDetail(r.roundName2, "bold");
		if (r.creativeCode != null) {
			appendToRoundDetail(r.creativeCode + " v" + r.creativeVersion, "bold");
			if (meta != null) {
				appendToRoundDetail(meta.title, null);
				appendToRoundDetail("TIME LIMIT: " + Core.pad0((int) (meta.timeLimitSec / 60)) + ":"
						+ String.format("%02d", meta.timeLimitSec % 60), null);
				if (meta.clearMS > 0)
					appendToRoundDetail("BEST: " + Core.pad0((int) (meta.clearMS / 60000)) + ":"
							+ Core.pad0((int) (meta.clearMS % 60000 / 1000))
							+ "." + String.format("%03d", meta.clearMS % 1000), "bold");
			}
		}
		if (r.topFinish != null) {
			long t = r.getTime(r.topFinish);
			appendToRoundDetail("TOP: " + Core.pad0((int) (t / 60000)) + ":" + Core.pad0((int) (t % 60000 / 1000))
					+ "." + String.format("%03d", t % 1000), "bold");
		}
		if (r.getMe() != null && r.getMe().finish != null && r.byId.get(r.myPlayerId) != null) {
			long t = r.getTime(r.getMe().finish);
			appendToRoundDetail("YOU: " + Core.pad0((int) (t / 60000)) + ":" + Core.pad0((int) (t % 60000 / 1000))
					+ "." + String.format("%03d", t % 1000) + " #" + r.byId.get(r.myPlayerId).ranking, "bold");
		}
		if (r.end != null) {
			long t = r.getTime(r.end);
			appendToRoundDetail("END: " + Core.pad0((int) (t / 60000)) + ":" + Core.pad0((int) (t % 60000 / 1000))
					+ "." + String.format("%03d", t % 1000), "bold");
		}
		if (r.teamScore != null) {
			appendToRoundDetail(Arrays.toString(r.teamScore), "bold");
		}
		if (r.isFinal()) {
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
						appendToRoundDetail((r.myPlayerId == p.id ? "★" : p.partyId != 0 ? "p " : "　") + " "
								+ (p.qualified == null ? "　" : p.qualified ? "○" : "✕") + Core.pad(p.score)
								+ "pt(" + (p.finalScore < 0 ? "  " : Core.pad(p.finalScore)) + ")" + " " + p,
								null);
				}
				appendToRoundDetail("********** solo rank **********", null);
			}
			appendToRoundDetail("rank " + (squads != null ? "sq " : "") + "  score     time pt   name", null);
			for (Player p : r.byRank()) {
				StringBuilder buf = new StringBuilder();
				buf.append(p.qualified == null ? "　" : p.qualified ? "○" : "✕");
				buf.append(Core.pad(p.ranking));
				if (squads != null)
					buf.append(" ").append(Core.pad(p.squadId));
				buf.append(" ").append(Core.pad(p.score)).append("pt(")
						.append(p.finalScore < 0 ? "  " : Core.pad(p.finalScore))
						.append(")");
				if (p.finish == null)
					buf.append("        ");
				else
					buf.append(" ").append(String.format("%3d", r.getTime(p.finish) / 1000)).append('.')
							.append(String.format("%03d", r.getTime(p.finish) % 1000));
				buf.append(" ").append(p.partyId != 0 ? Core.pad(p.partyId) : "  ");
				buf.append(" ").append(r.myPlayerId == p.id ? "★" : "　").append(p);
				appendToRoundDetail(new String(buf), null);
			}
		}
		if (meta != null)
			appendToRoundDetail(meta.description, null);
		roundDetailArea.setCaretPosition(0);
	}

	void displayStats() {
		statsArea.setText("");

		PlayerStat stat = Core.stat;
		appendToStats(Core.getRes("myStatLabel") + stat.winCount + " / " + stat.participationCount + " ("
				+ stat.getRate() + "%)", "bold");
		appendToStats("Total rate: " + stat.totalWinCount + " / " + stat.totalParticipationCount + " ("
				+ Core.calRate(stat.totalWinCount, stat.totalParticipationCount) + "%)", "bold");

		/* achievements is not supported yet.
		int p = stat.totalPoint();
		appendToStats("Total Points: " + p, "bold");
		appendToStats("[" + stat.getTitle() + "]", "bold");

		for (int i = 0; i < Core.titledPoints.length; i += 1) {
			if (p < Core.titledPoints[i]) {
				appendToStats("->" + Core.titledPoints[i] + " points", null);
				appendToStats("[" + Core.titles[i + 1] + "]", null);
				break;
			}
		}
		*/

		statsArea.setCaretPosition(0);
	}

	static final SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.JAPAN);

	void displayFooter() {
		String text = "";
		Match m = getSelectedMatch();
		if (m == null)
			m = Core.currentMatch;
		if (m == null)
			return;
		if (m.start != null) {
			text += "TIME:" + f.format(m.start) + (m.end == null ? "" : " - " + f.format(m.end));
		}
		if (m.winStreak > 0) {
			text += " WIN(" + m.winStreak + ")";
		}
		// server info
		text += " PING: " + m.pingMS + "ms " + m.ip;
		Map<String, String> server = Core.servers.get(m.ip);
		if (server != null)
			text += " " + server.get("country") + " " + server.get("regionName") + " " + server.get("city") + " "
					+ server.get("timezone");
		pingLabel.setText(text);
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

class ServerSocketMutex {
	int port;
	ServerSocket ss;
	int count = 0;

	public ServerSocketMutex() {
		this(65123);
	}

	public ServerSocketMutex(int port) {
		this.port = port;
	}

	public synchronized boolean hasLock() {
		return ss != null;
	}

	public synchronized boolean tryLock() {
		if (ss != null) {
			count++;
			return true;
		}
		try {
			ss = new ServerSocket(port);
			return true;
		} catch (IOException e) {
		}
		return false;
	}

	public synchronized void lock() {
		while (true) {
			if (tryLock())
				return;
			try {
				wait(100);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	public synchronized void unlock() {
		if (ss == null)
			return;
		if (count > 0) {
			count--;
			return;
		}
		try {
			ss.close();
		} catch (IOException e) {
		}
		ss = null;
	}
}

class UTF8Control extends Control {
	public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload)
			throws IllegalAccessException, InstantiationException, IOException {
		// The below is a copy of the default implementation.
		String bundleName = toBundleName(baseName, locale);
		String resourceName = toResourceName(bundleName, "properties");
		ResourceBundle bundle = null;
		InputStream stream = null;
		if (reload) {
			URL url = loader.getResource(resourceName);
			if (url != null) {
				URLConnection connection = url.openConnection();
				if (connection != null) {
					connection.setUseCaches(false);
					stream = connection.getInputStream();
				}
			}
		} else {
			stream = loader.getResourceAsStream(resourceName);
		}
		if (stream != null) {
			try {
				// Only this line is changed to make it to read properties files as UTF-8.
				bundle = new PropertyResourceBundle(new InputStreamReader(stream, "UTF-8"));
			} finally {
				stream.close();
			}
		}
		return bundle;
	}
}

class FastListModel<E> extends AbstractListModel<E> {
	protected ArrayList<E> delegate = new ArrayList<>();

	public int getSize() {
		return delegate.size();
	}

	public E getElementAt(int index) {
		return delegate.get(index);
	}

	public int size() {
		return delegate.size();
	}

	public void add(E element) {
		delegate.add(element);
	}

	public void fireAdded() {
		fireIntervalAdded(this, 0, size());
	}

	public void clear() {
		int index1 = delegate.size() - 1;
		delegate.clear();
		if (index1 >= 0) {
			fireIntervalRemoved(this, 0, index1);
		}
	}
}

class CreativesWindow extends JFrame {
	JTable table = new JTable(Core.tableModel) {
		public String getToolTipText(MouseEvent ev) {
			java.awt.Point p = ev.getPoint();
			int rowIndex = rowAtPoint(p);
			int colIndex = columnAtPoint(p);
			Object o = getValueAt(rowIndex, colIndex);
			String tip = "<html><body>";
			if (o == null)
				return null;
			CreativeMeta meta = Core.creativesList.get(convertRowIndexToModel(rowIndex));
			if (meta != null && meta.thumb != null)
				tip += "<img src='" + meta.thumb + "' width='512' height='256'><br>";
			String text = o.toString();
			int sepIndex = 44;
			while (text.length() > sepIndex) {
				int sep = text.indexOf("\\n"); // 明示的改行
				if (sep == -1 || sep > sepIndex) {
					sep = sepIndex;
					tip += text.substring(0, sep) + "<br>";
				} else {
					tip += text.substring(0, sep) + "<br>";
					sep += 2;
				}
				text = text.substring(sep);
			}
			tip += text;
			return tip;
		}
	};

	public CreativesWindow() {
		setTitle("Fall Guys Record: Creative Stages");
		SpringLayout l = new SpringLayout();
		Container p = getContentPane();
		p.setLayout(l);

		JLabel desc = new JLabel("left double click=copy code, right double click=reload stage info");
		p.add(desc);
		l.putConstraint(SpringLayout.WEST, desc, 10, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, desc, 10, SpringLayout.NORTH, p);

		JButton refreshButton = new JButton(Core.getRes("refresh"));
		refreshButton.setFont(new Font(FallGuysRecord.fontFamily, Font.PLAIN, FallGuysRecord.FONT_SIZE_BASE));
		p.add(refreshButton);
		l.putConstraint(SpringLayout.EAST, refreshButton, -4, SpringLayout.EAST, p);
		l.putConstraint(SpringLayout.NORTH, refreshButton, 3, SpringLayout.NORTH, p);
		refreshButton.addActionListener(ev -> {
			Core.tableModel.fireTableDataChanged();
		});
		JButton saveButton = new JButton(Core.getRes("save immediately"));
		saveButton.setFont(new Font(FallGuysRecord.fontFamily, Font.PLAIN, FallGuysRecord.FONT_SIZE_BASE));
		p.add(saveButton);
		l.putConstraint(SpringLayout.EAST, saveButton, -4, SpringLayout.WEST, refreshButton);
		l.putConstraint(SpringLayout.NORTH, saveButton, 3, SpringLayout.NORTH, p);
		saveButton.addActionListener(ev -> {
			Core.save();
		});
		JTextField codeText = new JTextField();
		codeText.setFont(new Font(FallGuysRecord.fontFamily, Font.PLAIN, FallGuysRecord.FONT_SIZE_BASE));
		codeText.setPreferredSize(new Dimension(120, 22));
		p.add(codeText);
		l.putConstraint(SpringLayout.EAST, codeText, -4, SpringLayout.WEST, saveButton);
		l.putConstraint(SpringLayout.NORTH, codeText, 4, SpringLayout.NORTH, p);
		codeText.addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent ev) {
				String code = codeText.getText().replaceFirst(".*(\\d\\d\\d\\d).*(\\d\\d\\d\\d).*(\\d\\d\\d\\d).*",
						"$1-$2-$3");
				if (!code.matches("\\d\\d\\d\\d-\\d\\d\\d\\d-\\d\\d\\d\\d"))
					return;
				CreativeMeta meta = Core.retreiveCreativeInfo(code, 0, true);
				if (meta != null) {
					Date now = new Date();
					meta.played = now;
					meta.lastPlayed = now;
					Core.tableModel.fireTableDataChanged();
					codeText.setText("");
				}
			}
		});
		JLabel codeLabel = new JLabel("CODE:");
		codeLabel.setFont(new Font(FallGuysRecord.fontFamily, Font.PLAIN, FallGuysRecord.FONT_SIZE_BASE));
		p.add(codeLabel);
		l.putConstraint(SpringLayout.EAST, codeLabel, -4, SpringLayout.WEST, codeText);
		l.putConstraint(SpringLayout.NORTH, codeLabel, 5, SpringLayout.NORTH, p);

		JScrollPane scroller = new JScrollPane(table);
		p.add(scroller);
		table.setFont(new Font(FallGuysRecord.fontFamily, Font.PLAIN, FallGuysRecord.FONT_SIZE_BASE + 2));
		l.putConstraint(SpringLayout.WEST, scroller, 10, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.EAST, scroller, -10, SpringLayout.EAST, p);
		l.putConstraint(SpringLayout.NORTH, scroller, 10, SpringLayout.SOUTH, desc);
		l.putConstraint(SpringLayout.SOUTH, scroller, -10, SpringLayout.SOUTH, p);

		table.setRowSorter(new TableRowSorter<TableModel>(Core.tableModel));
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent ev) {
				if (ev.getClickCount() < 2)
					return;
				int index = table.rowAtPoint(ev.getPoint());
				if (index < 0)
					return;
				int row = table.convertRowIndexToModel(index);
				CreativeMeta meta = Core.creativesList.get(row);

				if (ev.getButton() == 1) {
					Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
					clipboard.setContents(new StringSelection(meta.code), null);
				} else if (ev.getButton() == 3) {
					Core.retreiveCreativeInfo(meta.code, meta.version, true);
					Core.tableModel.fireTableDataChanged();
				}
			}
		});
		DefaultCellEditor ce = (DefaultCellEditor) table.getDefaultEditor(Object.class);
		ce.setClickCountToStart(1);

		table.getColumnModel().getColumn(0).setMinWidth(124);
		table.getColumnModel().getColumn(0).setMaxWidth(124);
		table.getColumnModel().getColumn(3).setMinWidth(80);
		table.getColumnModel().getColumn(3).setMaxWidth(84);
		table.getColumnModel().getColumn(5).setMaxWidth(30);
		table.getColumnModel().getColumn(6).setMaxWidth(30);
		table.getColumnModel().getColumn(9).setMaxWidth(48);

		UIManager.put("ToolTip.font", new Font(FallGuysRecord.fontFamily, Font.BOLD, 20));
	}

	@SuppressWarnings("unchecked")
	void loadTableStat() {
		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream("tablestate.dat"))) {
			Map<Integer, SortOrder> sort = (Map<Integer, SortOrder>) in.readObject();
			List<SortKey> sortKeys = new ArrayList<>();
			for (Map.Entry<Integer, SortOrder> o : sort.entrySet())
				sortKeys.add(new SortKey(o.getKey(), o.getValue()));
			table.getRowSorter().setSortKeys(sortKeys);

			// TableColumn を並び替えた状態で再セット
			TableColumnModel cModel = table.getColumnModel();
			int[] columnIndices = (int[]) in.readObject();
			TableColumn[] ordered = new TableColumn[cModel.getColumnCount()];
			int i = 0;
			for (; i < columnIndices.length; i += 1)
				ordered[i] = cModel.getColumn(columnIndices[i]);
			for (; i < ordered.length; i += 1)
				ordered[i] = cModel.getColumn(i);
			while (cModel.getColumnCount() > 0)
				cModel.removeColumn(cModel.getColumn(0)); // clear columns
			for (i = 0; i < ordered.length; i += 1)
				cModel.addColumn(ordered[i]);

			int[] columnWidth = (int[]) in.readObject();
			for (i = 0; i < columnIndices.length; i += 1)
				cModel.getColumn(i).setPreferredWidth(columnWidth[i]);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	void saveTableStat() {
		List<? extends SortKey> sortKeys = table.getRowSorter().getSortKeys();
		Map<Integer, SortOrder> sort = new LinkedHashMap<>();
		for (SortKey k : sortKeys)
			sort.put(k.getColumn(), k.getSortOrder());

		int[] columnIndices = new int[table.getColumnCount()];
		int[] columnWidths = new int[table.getColumnCount()];
		for (int i = 0; i < columnIndices.length; i += 1) {
			columnIndices[i] = table.getColumnModel().getColumn(i).getModelIndex();
			columnWidths[i] = table.getColumnModel().getColumn(i).getWidth();
		}

		try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream("tablestate.dat"))) {
			out.writeObject(sort);
			out.writeObject(columnIndices);
			out.writeObject(columnWidths);
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}
}
