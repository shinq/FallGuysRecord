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
import java.net.ServerSocket;
import java.net.URL;
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
import java.util.Timer;
import java.util.TimerTask;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

//?????????????????????????????????
class PlayerStat {
	String name; // for user identication
	String platform;
	Set<Match> matches = new HashSet<Match>(); // ??????match???
	int totalScore;
	int participationCount; // rate ?????????round ????????? match ??????RankingMaker ????????????
	int winCount; // rate ????????????????????????????????????RankingMaker ????????????
	Map<String, String> additional = new HashMap<String, String>(); // ???????????????????????????????????????

	public PlayerStat(String name, String platform) {
		this.name = name;
		this.platform = platform;
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
		return name;//  + "(" + platform + ")"
	}
}

//???????????????????????????????????????
class Player {
	String name; // for user identication
	String platform;
	int id; // id of current round (diferrent for each rounds)
	int objectId; // object id of current round (for score log)
	int squadId;
	int partyId;
	int ranking; // rank of current round

	Boolean qualified;
	int score; // ??????????????????????????????????????????????????????
	int finalScore = -1; // ????????????????????????????????????????????????

	Player(int id) {
		this.id = id;
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
			if (p.finalScore >= 0)
				score += p.finalScore;
			else
				score += p.score;
		}
		return score;
	}
}

class Round {
	Match match;
	boolean fixed; // ??????????????????????????????????????????
	private boolean isFinal;
	String name;
	String roundName2; // ????????????????????????
	long id; // ???????????????????????????????????????????????????????????????????????????????????? frame ?????????????????????????????????start ?????????????????????
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

	public RoundDef getDef() {
		return RoundDef.get(name);
	}

	public void add(Player p) {
		synchronized (Core.listLock) {
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

	public boolean isFinal() {
		if (isFinal)
			return true;
		// isFinal ???????????????????????????????????????
		if (roundName2 != null) {
			// ????????????????????????????????????????????????????????????????????????????????????
			if (roundName2.contains("_non_final"))
				return false;
			if (roundName2.contains("_final"))
				return true;
			/* squads final detection
			if (byId.size() < 9) {
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

			// FIXME: ?????????????????????????????????????????????????????????????????????????????????????????????
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
		}
		RoundDef def = RoundDef.get(name);
		if (def != null && def.isFinalNormally) // ???????????????????????????????????????????????????????????????????????????????????????
			return true;
		return false;
	}
}

// ??????????????????
class Match {
	boolean fixed; // ??????????????????????????????
	String name;
	long id; // ?????????????????????????????????????????????????????????????????????start ?????????????????????
	String ip;
	Date start;
	Date end;
	List<Round> rounds = new ArrayList<Round>();

	public Match(String name, long id, String ip) {
		this.name = name;
		this.id = id;
		this.ip = ip;
	}
}

enum RoundType {
	RACE, HUNT, SURVIVAL, TEAM
};

class RoundDef {

	public final String dispName;
	public final String dispNameJa;
	public final RoundType type;
	public final boolean isFinalNormally; // ???????????????????????????????????????

	public RoundDef(String name, String nameJa, RoundType type) {
		this(name, nameJa, type, false);
	}

	public RoundDef(String name, String nameJa, RoundType type, boolean isFinal) {
		dispName = name;
		dispNameJa = nameJa;
		this.type = type;
		this.isFinalNormally = isFinal;
	}

	static Map<String, RoundDef> roundNames = new HashMap<String, RoundDef>();
	static {
		roundNames.put("FallGuy_DoorDash", new RoundDef("Door Dash", "??????????????????", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_03", new RoundDef("Whirlygig", "????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_02_01", new RoundDef("Dizzy Heights", "??????????????????", RoundType.RACE));
		roundNames.put("FallGuy_ChompChomp_01", new RoundDef("Gate Crash", "????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_01", new RoundDef("Hit Parade", "?????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_SeeSaw_variant2", new RoundDef("See Saw", "?????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_Lava_02", new RoundDef("Slime Climb", "????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_DodgeFall", new RoundDef("Fruit Chute", "????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_TipToe", new RoundDef("Tip Toe", "?????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_04", new RoundDef("Knight Fever", "???????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_WallGuys", new RoundDef("Wall Guys", "?????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_BiggestFan", new RoundDef("Big Fans", "??????????????????", RoundType.RACE));
		roundNames.put("FallGuy_IceClimb_01", new RoundDef("Freezy Peak", "????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_Tunnel_Race_01", new RoundDef("Roll On", "???????????????", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_06", new RoundDef("Skyline Stumble", "?????????????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_ShortCircuit", new RoundDef("Short Circuit", "??????????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_HoverboardSurvival", new RoundDef("Hoverboard Heroes", "????????????????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_SlimeClimb_2", new RoundDef("Slimescraper", "??????????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_07", new RoundDef("Treetop Tumble", "??????????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_DrumTop", new RoundDef("Lily Leapers", "????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_SeeSaw360", new RoundDef("Full Tilt", "??????????????????", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_08", new RoundDef("Party Promenade", "?????????????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_PipedUp", new RoundDef("Pipe Dream", "?????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_05", new RoundDef("Tundra Run", "????????????????????????", RoundType.RACE));

		roundNames.put("FallGuy_TailTag_2", new RoundDef("Tail Tag", "???????????????", RoundType.HUNT));
		roundNames.put("FallGuy_Hoops_Blockade", new RoundDef("Hoopsie Legends", "?????????????????????????????????", RoundType.HUNT));
		roundNames.put("FallGuy_SkeeFall", new RoundDef("Ski Fall", "?????????????????????", RoundType.HUNT));
		roundNames.put("FallGuy_1v1_ButtonBasher", new RoundDef("Button Bashers", "???????????????????????????", RoundType.HUNT));
		roundNames.put("FallGuy_Penguin_Solos", new RoundDef("Pegwin Party", "????????????????????????????????????", RoundType.HUNT));
		roundNames.put("FallGuy_KingOfTheHill2", new RoundDef("Bubble Trouble", "?????????????????????", RoundType.HUNT));
		roundNames.put("FallGuy_Airtime", new RoundDef("Airtime", "??????????????????", RoundType.HUNT));
		roundNames.put("FallGuy_FollowTheLeader", new RoundDef("Leading Light", "???????????????????????????", RoundType.HUNT));

		roundNames.put("FallGuy_Block_Party", new RoundDef("Block Party", "???????????????????????????", RoundType.SURVIVAL));
		roundNames.put("FallGuy_JumpClub_01", new RoundDef("Jump Club", "?????????????????????", RoundType.SURVIVAL));
		roundNames.put("FallGuy_MatchFall", new RoundDef("Perfect Match", "???????????????????????????", RoundType.SURVIVAL));
		roundNames.put("FallGuy_Tunnel_01", new RoundDef("Roll Out", "??????????????????", RoundType.SURVIVAL));
		roundNames.put("FallGuy_SnowballSurvival", new RoundDef("Snowball Survival", "?????????????????????", RoundType.SURVIVAL));
		roundNames.put("FallGuy_FruitPunch", new RoundDef("Big Shots", "?????????????????????", RoundType.SURVIVAL));
		roundNames.put("FallGuy_RobotRampage_Arena2",
				new RoundDef("Stompin' Ground", "??????????????????????????????", RoundType.SURVIVAL));
		roundNames.put("FallGuy_FruitBowl", new RoundDef("Sum Fruit", "????????????????????????", RoundType.SURVIVAL));

		roundNames.put("FallGuy_ConveyorArena_01", new RoundDef("Team Tail Tag", "????????????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_TeamInfected", new RoundDef("Jinxed", "??????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_FallBall_5", new RoundDef("Fall Ball", "?????????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_BallHogs_01", new RoundDef("Hoarders", "??????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_RocknRoll", new RoundDef("Rock'N'Roll", "?????????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_Hoops_01", new RoundDef("Hoopsie Daisy", "?????????????????????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_EggGrab", new RoundDef("Egg Scramble", "??????????????????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_EggGrab_02", new RoundDef("Egg Siege", "???????????????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_Snowy_Scrap", new RoundDef("Snowy Scrap", "????????????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_ChickenChase_01", new RoundDef("Pegwin Pursuit", "????????????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_Basketfall_01", new RoundDef("Basketfall", "???????????????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_TerritoryControl_v2", new RoundDef("Power Trip", "?????????????????????", RoundType.TEAM));
		roundNames.put("FallGuy_Invisibeans", new RoundDef("Sweet Thieves", "??????????????????????????????", RoundType.TEAM));

		roundNames.put("FallGuy_FallMountain_Hub_Complete",
				new RoundDef("Fall Mountain", "???????????????????????????", RoundType.RACE, true));
		roundNames.put("FallGuy_FloorFall", new RoundDef("Hex-A-Gone", "?????????????????????", RoundType.SURVIVAL, true));
		roundNames.put("FallGuy_JumpShowdown_01",
				new RoundDef("Jump Showdown", "?????????????????????????????????", RoundType.SURVIVAL, true));
		roundNames.put("FallGuy_Crown_Maze_Topdown", new RoundDef("Lost Temple", "?????????????????????", RoundType.RACE, true));
		roundNames.put("FallGuy_Tunnel_Final", new RoundDef("Roll Off", "???????????????", RoundType.SURVIVAL, true));
		roundNames.put("FallGuy_Arena_01", new RoundDef("Royal Fumble", "???????????????????????????", RoundType.HUNT, true));
		roundNames.put("FallGuy_ThinIce", new RoundDef("Thin Ice", "?????????????????????", RoundType.SURVIVAL, true));

		roundNames.put("FallGuy_Gauntlet_09", new RoundDef("TRACK ATTACK", "????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_ShortCircuit2", new RoundDef("SPEED CIRCUIT", "???????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_SpinRing", new RoundDef("THE SWIVELLER", "?????????????????????", RoundType.SURVIVAL));
		roundNames.put("FallGuy_HoopsRevenge", new RoundDef("BOUNCE PARTY", "???????????????????????????", RoundType.HUNT));
		roundNames.put("FallGuy_1v1_Volleyfall", new RoundDef("VOLLEYFALL", "?????????????????????", RoundType.HUNT));
		roundNames.put("FallGuy_HexARing", new RoundDef("HEX-A-RING", "?????????????????????", RoundType.SURVIVAL, true));
		roundNames.put("FallGuy_BlastBall_ArenaSurvival",
				new RoundDef("BLAST BALL", "?????????????????????", RoundType.SURVIVAL, true));

		roundNames.put("FallGuy_SatelliteHoppers",
				new RoundDef("SATELLITE HOPPERS", "SATELLITE HOPPERS", RoundType.RACE));
		roundNames.put("FallGuy_Gauntlet_10", new RoundDef("TRACK ATTACK", "????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_Starlink", new RoundDef("STAR LINK", "????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_Hoverboard_Survival_2",
				new RoundDef("HOVERBOARD SURVIVAL", "????????????????????????", RoundType.RACE));
		roundNames.put("FallGuy_PixelPerfect", new RoundDef("PIXEL PERFECT", "????????????????????????", RoundType.HUNT));
	}

	public static RoundDef get(String name) {
		RoundDef def = roundNames.get(name);
		if (def == null)
			return new RoundDef(name, name, RoundType.RACE); // unknown stage
		return def;
	}
}

// ??????????????????????????????????????????????????????????????????
class RankingMaker {
	public String toString() {
		return "Final/Win";
	}

	public String getDesc() {
		return "Final???????????????????????????????????????????????????????????????????????????";
	}

	// ??????????????????????????????????????????????????????????????????????????????????????????????????????
	// calcTotalScore ???????????? true ??????????????????????????????
	public boolean isEnable(Round r) {
		return true;
	}

	// stat.participationCount / winCount / totalScore ??????????????????
	// ??????????????????????????????????????????????????? Maker ??????????????????
	// fixed round ??????????????????????????????????????????/????????????????????????????????????????????????????????????
	// default ????????????/?????????????????????final ???????????????????????????????????????
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.participationCount = stat.matches.size();
		if (r.isFinal()) {
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
			if (stat.totalScore <= 0) // ????????????????????????????????????????????????????????????????????????????????????????????????????????????
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
				buf.append(" ").append(stat).append("\n");
			}
		}
		buf.append("total: ").append(internalNo).append("\n");
		return new String(buf);
	}
}

//????????????????????????????????????????????????????????????????????????
class FeedFirstRankingMaker extends RankingMaker {
	@Override
	public String toString() {
		return "Feed Point";
	}

	@Override
	public String getDesc() {
		return "race/hunt ???????????????????????????????????????????????????????????????";
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.participationCount = stat.matches.size();
		if (r.isFinal()) {
			stat.totalScore += Core.PT_FINALS;
			if (p.qualified == Boolean.TRUE) {
				stat.winCount += 1;
				stat.totalScore += Core.PT_WIN;
			}
			return;
		}
		// ????????????????????????????????????
		RoundDef def = RoundDef.get(r.name);
		if (def.type == RoundType.RACE || def.type == RoundType.HUNT) {
			if (p.ranking == 1) // 1st
				stat.totalScore += Core.PT_1ST;
		}
	}
}

// ??????????????????????????????????????????????????????????????????
class SquadsRankingMaker extends RankingMaker {
	@Override
	public String toString() {
		return "Squads";
	}

	@Override
	public String getDesc() {
		return "squads ???????????????????????????????????????????????????????????????????????????????????????????????????????????????";
	}

	@Override
	public boolean isEnable(Round r) {
		// squadId ?????????????????????
		return r.byId.size() > 0 && r.byId.values().iterator().next().squadId != 0;
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.participationCount = stat.matches.size();
		if (r.isFinal()) {
			stat.totalScore += Core.PT_FINALS;
			// ??????????????????????????????????????????????????????????????????
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

// FallBall Cup ??????
class FallBallRankingMaker extends RankingMaker {
	@Override
	public String toString() {
		return "FallBall";
	}

	@Override
	public String getDesc() {
		return "FallBall ??????????????????";
	}

	@Override
	public boolean isEnable(Round r) {
		// fallball custom round ??????
		return r.name.equals("FallGuy_FallBall_5");
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.participationCount += 1; // ?????? round ???
		if (p.qualified == Boolean.TRUE) {
			stat.winCount += 1;
			stat.totalScore += 1;
		}
	}
}

// thieves ?????????????????????????????????????????????????????????
class CandyRankingMaker extends RankingMaker {
	@Override
	public String toString() {
		return "Sweet Thieves";
	}

	@Override
	public String getDesc() {
		return "Sweet Thieves ?????????????????????total ????????????????????????thief/guard ??????????????????????????????????????????????????????????????????????????????????????????";
	}

	@Override
	public boolean isEnable(Round r) {
		// thieves ??????
		return "FallGuy_Invisibeans".equals(r.name);
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.participationCount += 1;
		if (p.qualified == Boolean.TRUE)
			stat.winCount += 1;
		if (p.qualified == null)
			return; // ???????????????????????????????????????????????????????????????
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
				buf.append(Core.pad(dispNo)).append(" ").append(stat).append("\n");
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

// match ??????????????????????????????????????????????????????????????????????????????????????????????????????
class SnipeRankingMaker extends RankingMaker {
	@Override
	public String toString() {
		return "Snipes";
	}

	@Override
	public String getDesc() {
		return "????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????";
	}

	@Override
	public void calcTotalScore(PlayerStat stat, Player p, Round r) {
		stat.totalScore = stat.participationCount = stat.matches.size();
		if (r.isFinal() && p.qualified == Boolean.TRUE) {
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
	public static Map<String, Map<String, String>> servers = new HashMap<String, Map<String, String>>();

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
				//				if (!r.fixed)
				//					continue;
				if (!rankingMaker.isEnable(r))
					continue;
				// ????????????????????????????????????????????????
				for (Player p : r.byName.values()) {
					PlayerStat stat = stats.get(p.name);
					if (stat == null) {
						stat = new PlayerStat(p.name, p.platform);
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
			// ?????????????????? rate ????????????
			return (int) Math.signum(p2.getRate() - p1.getRate());
		}
	}

	static class StatComparatorWin implements Comparator<PlayerStat> {
		@Override
		public int compare(PlayerStat p1, PlayerStat p2) {
			int v = (int) Math.signum(p2.winCount - p1.winCount);
			if (v != 0)
				return v;
			// ?????????????????? rate ????????????
			v = (int) Math.signum(p2.getRate() - p1.getRate());
			if (v != 0)
				return v;
			// ?????????????????? score ????????????
			return (int) Math.signum(p2.totalScore - p1.totalScore);
		}
	}

	static class StatComparatorRate implements Comparator<PlayerStat> {
		@Override
		public int compare(PlayerStat p1, PlayerStat p2) {
			int v = (int) Math.signum(p2.getRate() - p1.getRate());
			if (v != 0)
				return v;
			// ?????????????????? score ????????????
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

	Timer survivalScoreTimer;

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
	//static Pattern patternMatchStart = Pattern.compile("\\[StateMatchmaking\\] Begin ");
	static Pattern patternRoundName = Pattern.compile(
			"\\[StateGameLoading\\] Loading game level scene ([^\\s]+) - frame (\\d+)");
	static Pattern patternLoadedRound = Pattern
			.compile("\\[StateGameLoading\\] Finished loading game level, assumed to be ([^.]+)\\.");

	static Pattern patternPlayerSpawn = Pattern.compile(
			"\\[CameraDirector\\] Adding Spectator target (.+) with Party ID: (\\d*)  Squad ID: (\\d+) and playerID: (\\d+)");
	static Pattern patternPlayerObjectId = Pattern.compile(
			"\\[ClientGameManager\\] Handling bootstrap for [^ ]+ player FallGuy \\[(\\d+)\\].+, playerID = (\\d+)");

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
			String ip = m.group(1);
			Core.addMatch(new Match(showName, id, ip));
			System.out.println("DETECT SHOW STARTING");
			readState = ReadState.ROUND_DETECTING;

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
						Map<String, String> server = Core.servers.get(ip);
						if (server == null) {
							ObjectMapper mapper = new ObjectMapper();
							JsonNode root = mapper.readTree(new URL("http://ip-api.com/json/" + ip));
							server = new HashMap<String, String>();
							server.put("country", root.get("country").asText());
							server.put("regionName", root.get("regionName").asText());
							server.put("city", root.get("city").asText());
							server.put("timezone", root.get("timezone").asText());
							Core.servers.put(ip, server);
						}
						System.err.println(
								ip + " " + Core.pingMS + " " + server.get("timezone") + " " + server.get("city"));
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
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
			m = patternRoundName.matcher(line);
			if (m.find()) {
				String roundName = m.group(1);
				long frame = Long.parseUnsignedLong(m.group(2)); // FIXME: round id ??????????????????
				Core.addRound(new Round(roundName, frame, isFinal, Core.getCurrentMatch()));
				System.out.println("DETECT STARTING " + roundName + " frame=" + frame);
				//readState = ReadState.MEMBER_DETECTING;
			}
			m = patternLoadedRound.matcher(line);
			if (m.find()) {
				String roundName2 = m.group(1);
				Core.getCurrentRound().roundName2 = roundName2;
				System.out.println("DETECT STARTING " + roundName2);
				readState = ReadState.MEMBER_DETECTING;
			}
			break;
		case MEMBER_DETECTING: // join detection
			// ?????? playerId, name ???????????????????????????????????????playerId, objectId ???????????????????????????????????????????????????????????????????????????????????????
			m = patternPlayerObjectId.matcher(line);
			if (m.find()) {
				int playerObjectId = Integer.parseUnsignedInt(m.group(1));
				int playerId = Integer.parseUnsignedInt(m.group(2));
				Player p = Core.getCurrentRound().byId.get(playerId);
				if (p == null) {
					p = new Player(playerId);
					Core.getCurrentRound().add(p);
				}
				p.objectId = playerObjectId;
				// System.out.println("playerId=" + playerId + " objectId=" + playerObjectId);
				break;
			}
			m = patternPlayerSpawn.matcher(line);
			if (m.find()) {
				String name = m.group(1);
				int partyId = m.group(2).length() == 0 ? 0 : Integer.parseUnsignedInt(m.group(2)); // ???????????????????????????
				int squadId = Integer.parseUnsignedInt(m.group(3));
				int playerId = Integer.parseUnsignedInt(m.group(4));
				int sep = name.indexOf("_");
				String playerName = name.substring(sep + 1).replaceFirst(" \\(.+\\)$", "");
				String platform = name.substring(0, sep);

				Player p = Core.getCurrentRound().byId.get(playerId);
				if (p == null) {
					p = new Player(playerId);
				}
				p.partyId = partyId;
				p.squadId = squadId;
				p.name = playerName;
				p.platform = platform;
				Core.getCurrentRound().add(p);

				System.out.println(Core.getCurrentRound().byId.size() + " Player " + playerName + " (id=" + playerId
						+ " squadId=" + squadId + ") spwaned.");
				listener.roundUpdated();
				break;
			}
			if (line.contains("[StateGameLoading] Starting the game.")) {
				listener.roundStarted();
			}
			if (line.contains("[GameSession] Changing state from Countdown to Playing")) {
				Core.getCurrentRound().start = getTime(line);
				listener.roundStarted();
				qualifiedCount = eliminatedCount = 0; // reset
				readState = ReadState.RESULT_DETECTING;
				if (Core.getCurrentRound().getDef().type == RoundType.SURVIVAL) {
					survivalScoreTimer = new Timer();
					survivalScoreTimer.scheduleAtFixedRate(new TimerTask() {
						@Override
						public void run() {
							for (Player p : Core.getCurrentRound().byId.values()) {
								if (p.qualified == Boolean.FALSE)
									continue;
								p.score += 1;
							}
							listener.roundUpdated();
						}
					}, 1000, 1000);
				}
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
							// ????????????????????????????????????????????????
							switch (RoundDef.get(r.name).type) {
							case RACE:
							case HUNT:
								player.score = r.byId.size() - qualifiedCount;
								break;
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
			// round over ????????????????????????????????????
			m = patternPlayerResult2.matcher(line);
			if (m.find()) {
				int playerId = Integer.parseUnsignedInt(m.group(1));
				int finalScore = Integer.parseUnsignedInt(m.group(2));
				boolean isFinal = "True".equals(m.group(3));
				Player player = Core.getCurrentRound().byId.get(playerId);
				System.out.println(
						"Result for " + playerId + " score=" + finalScore + " isFinal=" + isFinal + " " + player);
				if (player != null) {
					if (player.squadId > 0) { // ????????? squad ?????????????????????????????????????????????
						player.finalScore = finalScore;
					}
				}
				break;
			}
			// round end
			//if (text.contains("[ClientGameManager] Server notifying that the round is over.")
			if (line.contains(
					"[GameSession] Changing state from Playing to GameOver")) {
				Core.getCurrentRound().end = getTime(line);
				if (survivalScoreTimer != null) {
					survivalScoreTimer.cancel();
					survivalScoreTimer.purge();
					survivalScoreTimer = null;
				}
			}
			if (line.contains(
					"[GameStateMachine] Replacing FGClient.StateGameInProgress with FGClient.StateQualificationScreen")
					|| line.contains(
							"[GameStateMachine] Replacing FGClient.StateGameInProgress with FGClient.StateVictoryScreen")) {
				System.out.println("DETECT END GAME");
				Core.getCurrentRound().fixed = true;
				Core.updateStats();
				listener.roundDone();
				readState = ReadState.ROUND_DETECTING;
				break;
			}
			if (line.contains("== [CompletedEpisodeDto] ==")) {
				// ?????? kudos ?????????????????????????????????????????????????????????????????????????????????????????????????????????

				break;
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

	static ServerSocketMutex mutex = new ServerSocketMutex(29878);
	static FallGuysRecord frame;
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
			Core.servers = (Map<String, Map<String, String>>) in.readObject();
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

		JLabel label = new JLabel("???????????????????????????");
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
		rankingSortSel.addItem("????????????");
		rankingSortSel.addItem("????????????");
		rankingSortSel.addItem("?????????");
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
		rankingFilterSel.addItem(2);
		rankingFilterSel.addItem(3);
		rankingFilterSel.addItem(5);
		rankingFilterSel.addItem(10);
		rankingFilterSel.addItem(20);
		rankingFilterSel.addItem(25);
		rankingFilterSel.addItem(30);
		rankingFilterSel.addItemListener(ev -> {
			displayRanking();
		});
		p.add(rankingFilterSel);
		label = new JLabel("???????????????????????????");
		label.setFont(new Font(fontFamily, Font.PLAIN, 12));
		l.putConstraint(SpringLayout.WEST, label, 4, SpringLayout.EAST, rankingFilterSel);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(120, 20);
		p.add(label);

		label = new JLabel("?????????????????????");
		label.setFont(new Font(fontFamily, Font.BOLD, 14));
		l.putConstraint(SpringLayout.WEST, label, COL2_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);
		label = new JLabel("????????????????????????");
		label.setFont(new Font(fontFamily, Font.BOLD, 14));
		l.putConstraint(SpringLayout.WEST, label, COL3_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);
		label = new JLabel("????????????????????????");
		label.setFont(new Font(fontFamily, Font.BOLD, 14));
		l.putConstraint(SpringLayout.WEST, label, COL4_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.NORTH, label, LINE1_Y, SpringLayout.NORTH, p);
		label.setSize(100, 20);
		p.add(label);

		// under
		myStatLabel = new JLabel("");
		myStatLabel.setFont(new Font(fontFamily, Font.BOLD, 18));
		l.putConstraint(SpringLayout.WEST, myStatLabel, COL1_X, SpringLayout.WEST, p);
		l.putConstraint(SpringLayout.SOUTH, myStatLabel, -10, SpringLayout.SOUTH, p);
		myStatLabel.setPreferredSize(new Dimension(330, 28));
		p.add(myStatLabel);

		pingLabel = new JLabel("");
		pingLabel.setFont(new Font(fontFamily, Font.PLAIN, 16));
		l.putConstraint(SpringLayout.WEST, pingLabel, 40, SpringLayout.EAST, myStatLabel);
		l.putConstraint(SpringLayout.SOUTH, pingLabel, -10, SpringLayout.SOUTH, p);
		pingLabel.setPreferredSize(new Dimension(900, 20));
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
		playerMarkingSel.addItem("underscore");
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

		JButton removeMemberFromRoundButton = new JButton("????????????????????????????????????");
		removeMemberFromRoundButton.setFont(new Font(fontFamily, Font.BOLD, 14));
		l.putConstraint(SpringLayout.WEST, removeMemberFromRoundButton, 10, SpringLayout.EAST, playerMarkingSel);
		l.putConstraint(SpringLayout.NORTH, removeMemberFromRoundButton, 0, SpringLayout.NORTH, playerSel);
		removeMemberFromRoundButton.setPreferredSize(new Dimension(180, 20));
		removeMemberFromRoundButton.addActionListener(ev -> removePlayerOnCurrentRound());
		p.add(removeMemberFromRoundButton);

		JButton removeMemberFromMatchButton = new JButton("?????????????????????????????????");
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
					out.writeObject(Core.servers);
				} catch (IOException ex) {
					ex.printStackTrace();
				}
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
				for (String city : connected.keySet()) {
					System.err.println(city + "\t" + connected.get(city));
				}
				for (Match m : Core.matches) {
					System.err.println("****** " + m.name);
					for (Round r : m.rounds) {
						System.err.println(r.name + "\t" + r.roundName2);
					}
				}
			}
		});

		// start log read
		reader = new FGReader(
				new File(FileUtils.getUserDirectory(), "AppData/LocalLow/Mediatonic/FallGuys_client/Player.log"), this);
		reader.start();
	}

	void updateMatches() {
		// server info
		String text = "PING: " + Core.pingMS + "ms " + Core.serverIp;
		Map<String, String> server = Core.servers.get(Core.serverIp);
		if (server != null)
			text += " " + server.get("country") + " " + server.get("regionName") + " " + server.get("city") + " "
					+ server.get("timezone");
		pingLabel.setText(text);

		int prevSelectedIndex = matchSel.getSelectedIndex();
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
						appendToRoundDetail((Core.myName.equals(p.name) ? "???" : p.partyId != 0 ? "p " : "???") + " "
								+ (p.qualified == null ? "???" : p.qualified ? "???" : "???") + Core.pad(p.score)
								+ "pt(" + (p.finalScore < 0 ? "  " : Core.pad(p.finalScore)) + ")" + " " + p,
								Core.playerStyles.get(p.name));
				}
				appendToRoundDetail("********** solo rank **********", null);
			}
			appendToRoundDetail("rank " + (squads != null ? "sq " : "") + "  score  pt   name", null);
			for (Player p : r.byRank()) {
				StringBuilder buf = new StringBuilder();
				buf.append(p.qualified == null ? "???" : p.qualified ? "???" : "???");
				buf.append(Core.pad(p.ranking)).append(" ");
				if (squads != null)
					buf.append(Core.pad(p.squadId)).append(" ");
				buf.append(Core.pad(p.score)).append("pt(").append(p.finalScore < 0 ? "  " : Core.pad(p.finalScore))
						.append(")").append(" ").append(p.partyId != 0 ? Core.pad(p.partyId) + " " : "   ");
				buf.append(Core.myName.equals(p.name) ? "???" : "???").append(p);
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
			if (m.rounds.size() <= roundIndex)
				return null;
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
					.setText("???????????????: " + own.winCount + " / " + own.participationCount + " (" + own.getRate() + "%)");
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

	/**
	 * ?????????????????????????????????????????????????????????
	 */
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
