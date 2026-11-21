package com.ukulele.monitor;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import android.widget.Switch;

import com.ukulele.monitor.ai.AiCredentials;
import com.ukulele.monitor.ai.AiScoreAssistant;
import com.ukulele.monitor.ai.ScoreImageDocument;
import com.ukulele.monitor.ai.AiScoreImportDialog;
import com.ukulele.monitor.ai.AiSetupDialog;
import com.ukulele.monitor.ai.AiChatDialog;
import com.ukulele.monitor.ai.CompanionEngine;
import com.ukulele.monitor.ai.CompanionPersona;
import com.ukulele.monitor.ai.CompanionStore;
import com.ukulele.monitor.ai.DeepSeekClient;
import com.ukulele.monitor.ai.LightDirector;
import com.ukulele.monitor.fun.ChordLibrary;
import com.ukulele.monitor.fun.ChordPlayer;
import com.ukulele.monitor.fun.FunStore;
import com.ukulele.monitor.fun.MetronomeEngine;
import com.ukulele.monitor.fun.MusicTrivia;
import com.ukulele.monitor.fun.TunerMath;
import com.ukulele.monitor.ui.AppDialogs;
import com.ukulele.monitor.ui.AppTheme;
import com.ukulele.monitor.ui.AudioScopeView;
import com.ukulele.monitor.ui.ChordDiagramView;
import com.ukulele.monitor.ui.ConfettiView;
import com.ukulele.monitor.ui.Feedback;
import com.ukulele.monitor.ui.LedPreviewView;
import com.ukulele.monitor.ui.SettingsStore;
import com.ukulele.monitor.ui.SettingsNavigator;
import com.ukulele.monitor.ui.SettingsIllustrationView;
import com.ukulele.monitor.ui.SplashOverlay;
import com.ukulele.monitor.ui.TunerGaugeView;
import com.ukulele.monitor.ui.UiKit;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * 手机端控制台：复用屏幕的音量/音色/LED 控制，并显示 FPGA 计算的完整频谱、波形与延迟音频 XY。
 * 只使用 Android 平台 BLE/Canvas API，不依赖第三方运行库。
 */
public class MainActivity extends Activity {
    private static final UUID SERVICE = UUID.fromString("6b1e0001-8f3a-4c2b-9d5e-1a2b3c4d5e6f");
    private static final UUID STATUS = UUID.fromString("6b1e0002-8f3a-4c2b-9d5e-1a2b3c4d5e6f");
    private static final UUID EVENT = UUID.fromString("6b1e0003-8f3a-4c2b-9d5e-1a2b3c4d5e6f");
    private static final UUID COMMAND = UUID.fromString("6b1e0004-8f3a-4c2b-9d5e-1a2b3c4d5e6f");
    private static final UUID SPECTRUM = UUID.fromString("6b1e0006-8f3a-4c2b-9d5e-1a2b3c4d5e6f");
    private static final UUID WAVE = UUID.fromString("6b1e0007-8f3a-4c2b-9d5e-1a2b3c4d5e6f");
    private static final UUID SONG = UUID.fromString("6b1e0008-8f3a-4c2b-9d5e-1a2b3c4d5e6f");
    private static final UUID SONG_STATUS = UUID.fromString("6b1e0009-8f3a-4c2b-9d5e-1a2b3c4d5e6f");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQ_PERM = 31;
    private static final int REQ_FILE = 32;
    /** v3.12.0：AI 曲谱导入的图片选择；AI 语音的麦克风权限请求。 */
    private static final int REQ_AI_IMAGE = 82;
    private static final int REQ_AI_DOCUMENT = 83;
    private static final int REQ_BROWSER_SCORE = 84;
    private static final int REQ_VOICE_PACK = 86;
    private TextView voicePackStatus;
    private boolean voicePackImporting;
    private static final int REQ_AI_VOICE_PERM = 83;

    private final Handler main = new Handler(Looper.getMainLooper());

    // ==== 设计系统 / 应用设置 ====
    private UiKit kit;
    private SettingsStore settings;
    private Feedback feedback;
    private LinearLayout rootView;
    private boolean restoringSong;
    /** 程序化选中曲目（进入曲谱页默认选第一首）期间不覆写“上次曲目”记录。 */
    private boolean programmaticSelection;
    private SettingsStore.Listener settingsListener;
    private TextView[] textScaleButtons;
    private TextView[] leadButtons;
    private TextView[] noteStyleButtons;
    private TextView deviceStatusLabel, deviceSubtitleLabel;
    private TextView devicePillLabel;
    private TextView connectBtn;
    private TextView lastDeviceLabel, connectionHelpLabel;
    private TextView versionLabel;
    private Switch showFingeringSwitch, keepScreenOnSwitch, hapticsSwitch, soundSwitch,
            autoConnectSwitch, rememberSongSwitch;
    private Switch glideSwitch,vibratoSwitch;
    private TextView gestureStatus, vibratoSourceButton;
    private TouchState touchState;
    private SeekBar touchThresholdBar,touchGraceBar;
    private TextView touchThresholdLabel,touchThresholdStatus,touchThresholdApply;
    private TextView touchGraceLabel,touchStatus,touchApply,touchFast64,touchFast128,touchQuick;
    private Switch touchGraceSwitch;
    private enum TouchEdit { THRESHOLD, TIMING }
    private TouchEdit pendingTouchEdit;
    private boolean updatingTouchUi,touchThresholdDirty,touchTimingDirty,touchExpired;
    private int touchThresholdDraft=360,touchFastDraft=0,touchGraceDraft=0,touchTicket;
    private Runnable touchTimeout;
    private final Runnable touchStateExpired=()->{touchExpired=true;updateTouchUi();};
    private FxState fxState;
    private boolean fxExpired=true;
    private final Runnable fxStateExpired=()->{fxExpired=true;updateChainUi();};

    private GestureState gestureState;
    private boolean updatingGestureUi,gesturePending;
    private int gestureTicket;
    private Runnable gestureTimeout;
    private boolean gestureExpired;
    private final Runnable gestureStateExpired=()->{gestureExpired=true;updateGestureUi();updateTouchUi();};
    private View statusDot;
    private boolean statusReliable;
    private float uiScale = 1f;
    /** 页面内卡片之间的垂直间距（dp，设计契约 12）。 */
    private int contentParams = 12;

    private BluetoothAdapter adapter;
    private volatile BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandChar;
    private BluetoothGattCharacteristic songChar;
    private BluetoothGattCharacteristic settingsChar;
    private static final String READ_SETTINGS = "@READ_SETTINGS";
    private Runnable stateSyncRetry;
    private final Queue<byte[]> songQueue = new ArrayDeque<>();
    private boolean songWriteBusy;
    private boolean bleReady;
    private boolean scanning;
    private boolean notificationsBusy;
    private int negotiatedMtu = 23;
    private boolean mtuSettled;
    private Runnable bleTimeout;
    private final Queue<BluetoothGattDescriptor> notifyQueue = new ArrayDeque<>();
    private final ControlCommandQueue commandQueue = new ControlCommandQueue();
    private BluetoothGattCharacteristic activeWrite;
    private Runnable writeTimeout;
    /**
     * 写入代次：每次重新起表自增，并把当次的代次交给超时回调。
     * 超时只允许判定「它自己那一次写入」，否则旧定时器会误伤后续写入
     * （看门狗以「最近一次成功写完」为基准续期，会有多个定时器以不同代次共存）。
     */
    private long writeGeneration;
    /** 非预期掉线的自动重连：上限次数与待执行的 Runnable。 */
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    /** 判定「这次连接算稳定」需要持续在线的时长；只有稳定过才允许重置重连计数。 */
    private static final long STABLE_CONNECTION_MS = 30000L;
    /** 写入进度看门狗：多久没有任何一包写完就认为该写入卡死（比原来的 8s 略宽松，减少误判）。 */
    private static final long WRITE_STALL_TIMEOUT_MS = 12000L;
    /**
     * 看门狗是否正在计时。必须用独立布尔量而不是「writeTimeout != null」判断：
     * removeCallbacks 之后字段仍非空（指向已失效的 Runnable），用它当判据会让后续写入完全没有看门狗。
     */
    private boolean writeWatchdogArmed;
    private int reconnectAttempts;
    private Runnable reconnectRunnable;
    /** 最近一次进入可用状态（bleReady）的时刻，用于判定连接是否稳定。 */
    private long bleReadySince;
    /** 当前正在使用的扫描器，用于「重复点击扫描」时先停掉旧扫描。 */
    private BluetoothLeScanner oldScanner;
    /** 扫描超时回调与代次：重启扫描时必须能撤掉上一轮的定时器。 */
    private Runnable scanTimeout;
    private int scanGeneration;

    private int writeStartRetries;
    private TextView statusText;
    private TextView headerTitle;
    private TextView headerSubtitle;
    private FrameLayout pageHost;
    private AudioScopeView visualizer;
    private AudioAnalysisFrame audioAnalysis=new AudioAnalysisFrame();
    private final AudioAnalysisFrame analysisParser=new AudioAnalysisFrame();
    private final byte[] analysisPacket=new byte[20];
    private final java.util.concurrent.ExecutorService audioWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int analysisEpoch;
    private int parserEpoch=-1;
    private final Object audioFrameLock=new Object();
    private AudioAnalysisFrame pendingAudioFrame;
    private BluetoothGatt pendingAudioOrigin;
    private int pendingAudioEpoch;
    private boolean audioRenderPosted;
    private final Runnable renderAudioFrame=()->{
        AudioAnalysisFrame frame;BluetoothGatt origin;int epoch;
        synchronized(audioFrameLock){frame=pendingAudioFrame;origin=pendingAudioOrigin;epoch=pendingAudioEpoch;pendingAudioFrame=null;audioRenderPosted=false;}
        if(frame==null || origin!=gatt || epoch!=analysisEpoch || isDestroyed() || !powerAllowsControls())return;
        audioAnalysis=frame;analysisFresh=true;if(!frame.scopeOnly)peakHz=(int)Math.round(frame.peakHz());sampleRate=48828;
        if(visualizer!=null)visualizer.update(frame);
        if(!frame.scopeOnly&&this.peakMetric!=null)this.peakMetric.setText(String.format(java.util.Locale.ROOT,"%.1f\nHz",frame.peakHz()));
        if(this.sampleMetric!=null)this.sampleMetric.setText("48,828.125\nHz");
        // 调音器复用同一份完整分析帧：只做 bin 抛物线插值，不新增蓝牙订阅，也不改数据解析。
        // 字段在本类中声明于本初始化器之后，必须写 this.，否则 javac 报「非法前向引用」。
        if(!frame.scopeOnly&&this.tunerGauge!=null && this.currentPage==PAGE_FUN && this.activityVisible) updateTunerFromAnalysis(true);
        main.removeCallbacks(this.analysisExpired);main.postDelayed(this.analysisExpired,3500);
    };
    private boolean analysisFresh;
    private int audioDisplayMode;
    private final Runnable analysisExpired=()->{analysisFresh=false;if(visualizer!=null)visualizer.clear();if(this.peakMetric!=null)this.peakMetric.setText("等待数据");if(this.tunerGauge!=null)updateTunerFromAnalysis(false);};
    private LedPreviewView ledPreview;
    private TextView songStatusLabel;
    private TextView modeLockButton,modePolyButton,modeStatusText,presetStatusText,operationStatusText;
    private int playModeTicket;
    private LiveNoteState liveNoteState;
    private final PlayedNoteTracker playedNoteTracker=new PlayedNoteTracker();
    private PerformanceTake performanceTake;
    private TextView takeStatus,takeRecord,takeSave;
    private View takeCard;
    private PracticeScorer practiceScorer;
    private PracticeScoreRecords scoreRecords;
    private PracticeScoreRecords.Record bestScore;
    private Switch scoringSwitch;
    private TextView scoreValue,scoreDetail,scoreBest,scoreRetry,fullScoreLabel;
    private boolean scoringEnabled,scorePaused,pausingPractice,scoreSaving;
    private double scoreRate=1;
    private long scoreStartedAt,scoreElapsedBase;
    private int scoreGeneration;
    private String visibleScoreKey,scoreProfile,scoreHint="";
    private final Runnable scoreTick=new Runnable(){public void run(){tickScoring();}};
    private final Runnable scoreHealthTick=new Runnable(){public void run(){if(activityVisible){updateScoringUi();main.postDelayed(this,400);}}};
    private TextView selectedSongLabel, songMetaLabel, songProgressLabel, songGuideModeLabel, songPlayButton;
    /** v3.12.0 练习页重排：顶部「本次练习」摘要卡。 */
    private TextView practiceSongLabel, practiceSongMetaLabel, practiceSongStatus;
    private final ArrayList<SongLibrary.Song> librarySongs = new ArrayList<>();
    /** 曲库行：容器与文字分开保存，选中态同时改背景与文字色。 */
    private static final class LibraryRow {
        final String songId; final LinearLayout container; final TextView title; final TextView meta;
        LibraryRow(String id, LinearLayout c, TextView t, TextView m) { songId = id; container = c; title = t; meta = m; }
    }
    private final ArrayList<LibraryRow> libraryRows = new ArrayList<>();
    private LinearLayout libraryContainer;
    private ScorePageList practicePageList;
    private TextView libraryPrevious, libraryNext, libraryPageLabel;
    private static final int LIBRARY_PAGE_SIZE = 5;
    private TextView libraryCountLabel;
    private String libraryQuery = "";
    private int libraryFilter;
    private int libraryCategory, libraryPage;
    private final Runnable librarySearchRefresh=()->{libraryPage=0;rebuildLibraryList();};
    private String libraryLoadError;
    private SongGuideView songGuideView;
    private SongTimeline selectedTimeline;
    private SongTimeline deviceLightTimeline; // Actual uploaded light projection has additional REST snapshots.
    private byte[] selectedSongBytes;
    private String selectedSongId, selectedSongName;
    private String pendingUploadId, pendingUploadName, uploadedSongId;
    private boolean uploadTransportDone,uploadDeviceConfirmed;
    private int uploadSize;
    private Runnable uploadAckTimeout;
    private String queuedPlayId, hardwarePlayingId;
    private long playRequestSequence, queuedPlayToken, activePlayToken;
    private String activePlayId;
    private ScoreRepository scoreRepo;
    private LinearLayout savedScores,songLibraryPanel;
    private TextView librarySectionButton,practiceSectionButton;
    private String selectedSavedId,exportScoreId;
    private final java.util.concurrent.ExecutorService scoreWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final int REQ_SCORE_VIEW=71,REQ_SCORE_EXPORT=72,REQ_SCORE_LIBRARY=73;
    private int lastNightMode;
    private double guideSpeed=1.0,guideElapsedBase;
    private boolean songTempoSupported,deviceStopRequested;
    private boolean instrumentIntent=false,pendingStartInstrument=false;
    private SongPlaybackState deviceSongState;
    private int songControlSequence,pendingSongControlToken,pendingSongControlOp,activeDeviceSession;
    private Runnable songControlTimeout;
    private String pendingSongControlLabel;
    private android.app.Dialog fullScreenDialog;
    private final PracticeCountdown fullCountdown=new PracticeCountdown();
    private Runnable fullCountdownTick;
    private boolean fullCountdownDevice;
    private TextView fullNoteLabel;
    private View fullPracticeOptions;
    private ImageButton fullPracticeGear;
    private boolean localGuidePaused, restartWithLights;
    private boolean fullPractice,ledAnimationPaused;
    private TextView guideSceneButton, fullGuideSceneButton;
    private JamCanvasView jamCanvas;
    private android.app.Dialog jamDialog;
    private static final int REQ_PAINT_EXPORT=88;
    private android.graphics.Bitmap paintToExport;
    private Runnable paintingDemo;
    private static final String[] CAREER_IDS={"theme_little_bee","twinkle_twinkle","ode_to_joy","theme_mo_li_hua","jingle_bells"};
    private static final String[] CAREER_STAGES={"萤火初醒","星光入弦","穿过云海","月下花庭","星环终章"};
    private static final int[] CAREER_PASS={60,65,70,75,80};
    private int careerStage=-1;
    private PracticeScorer careerScorer;
    private TextView guideDirectionButton,fullDirectionButton;
    private long importedSongSequence, guideStartedAt;
    private boolean guideRunning, guideDeviceReference, activityVisible;
    private final Runnable guideTick = new Runnable() {
        @Override public void run() {
            if (!guideRunning || selectedTimeline == null) return;
            long elapsed = Math.min(selectedTimeline.durationMs, currentPracticeElapsed());
            renderGuideProgress(elapsed,selectedTimeline.durationMs,guideSpeed);
            if (elapsed >= selectedTimeline.durationMs && !guideDeviceReference) {
                guideElapsedBase=selectedTimeline.durationMs;
                guideRunning = false;main.removeCallbacks(practiceLease);if(!guideDeviceReference&&bleReady)writeCommand("PRACTICE:0");
                songGuideModeLabel.setText(guideDeviceReference ? "本地进度已结束 · 设备停止状态未确认" : "本地跟弹预览已结束，未下发设备");
            } else main.postDelayed(this, 32);
        }
    };
    private TextView volumeValue;
    private TextView speedValue;
    private TextView brightnessValue;
    private SeekBar volumeBar, speedBar, brightnessBar;
    private TextView ukeButton, guitarButton, rowAxisButton, colAxisButton, ledApplyButton;
    private TextView[] ledModeButtons;
    private TextView[] presetButtons = new TextView[4];
    private ControllerState controllerState;
    private LedBitmapState bitmapState;
    private final LedBitmapState.Assembler bitmapAssembler=new LedBitmapState.Assembler();
    private final java.util.ArrayDeque<String> profilePackets=new java.util.ArrayDeque<>();
    private final Runnable profileSender=new Runnable(){public void run(){if(!bleReady||bitmapPendingTicket==0){profilePackets.clear();return;}if(!profilePackets.isEmpty()&&commandQueue.size()<4){String c=profilePackets.peek();if(writeCommand(c))profilePackets.poll();}if(!profilePackets.isEmpty())main.postDelayed(this,65);}};
    private TextView bitmapStatusText;
    private LedBitmapEditor bitmapEditor;
    private int bitmapTicket,bitmapPendingTicket;
    private LedProfile bitmapPendingProfile;
    private Runnable bitmapTimeout;
    private boolean settingsSynced, volumeDragging, ledDraftDirty;
    private Runnable stateSyncTimeout;
    private int currentVolume = 20, currentTimbre = 0, ledFlags;
    private TextView peakMetric;
    private TextView sampleMetric;
    private TextView guideMetric;
    private TextView noteMetric;
    private TextView ledNoteLabel;
    // Library and practice have separate tabs; all navigation storage must match.
    private static final int PAGE_COUNT = 6;
    private TextView[] navItems = new TextView[PAGE_COUNT];
    private ImageView[] navIcons = new ImageView[PAGE_COUNT];
    private View[] pages = new View[PAGE_COUNT];
    private int currentPage;
    private int selectedLedMode = 0;
    private int selectedLedAxis = 0;
    private int ledSpeed = 24;
    private int ledBrightness = 180;
    private boolean ledLandscape = true;
    private String currentNote = "--", guideNote="--";
    private int midiSkipped;
    private EditText urlText;
    private long downloadId = -1;

    // ==================== v3.11.0：第 6 个底部入口「乐趣」、启动动画与彩蛋 ====================
    /** 「乐趣」页下标。页面只做手机侧玩法（调音器/节拍器/和弦/问答/成就），不新增任何设备协议。 */
    private static final int PAGE_FUN = 4;
    /** 设置页下标。显式常量避免把「4=设置」的旧顺序写死在新代码里。 */
    private static final int PAGE_SETTINGS = 5;
    private SettingsNavigator settingsNavigator;
    private String settingsModuleId = "";
    private final java.util.List<SettingsIllustrationView> settingsIllustrations = new ArrayList<>();
    // 成就 id：与 FunStore.badges() 中的 id 一致。集中成常量，避免 UI 侧写错字符串。
    private static final String ACH_FIRST_CONNECT = "first_connect";
    private static final String ACH_FIRST_UPLOAD = "first_upload";
    private static final String ACH_FIRST_PLAY = "first_play";
    private static final String ACH_FIRST_SCORE = "first_score";
    private static final String ACH_FIRST_LED = "first_led";
    private static final String ACH_CHORD_EXPLORER = "chord_explorer";
    private static final String ACH_METRONOME_MASTER = "metronome_master";
    private static final String ACH_QUIZ_ACE = "quiz_ace";
    private static final String ACH_STREAK_3 = "streak_3";
    private static final String ACH_EGG_HUNTER = "egg_hunter";
    /** 底部导航容器：选中项用 accentSoft 圆角底衬，只改外观，不改变页面切换逻辑。 */
    private final LinearLayout[] navContainers = new LinearLayout[PAGE_COUNT];
    private int navSelectedPage = -1;
    private FunStore funStore;
    private PracticeJourneyStore journey;
    private PracticeGamesDialog practiceGame;
    private TextView journeyTitle,journeyTasks,journeyWeek,gameBests;
    private ProgressBar journeyProgress;
    private final Random funRandom = new Random();
    /** 音频玩法对象延迟创建：不进入「乐趣」页就不占用 AudioTrack。 */
    private MetronomeEngine metronome;
    private ChordPlayer chordPlayer;
    // 启动加载动画：只在进程内第一次构建界面时出现；主题/字号重建界面不重播。
    private FrameLayout appShell;
    private SplashOverlay splashOverlay;
    /** 进程内第一次 buildUi() 才允许判断是否播放启动动画；主题/字号重建不会重播（复核 P2-1）。 */
    private boolean firstUiBuild = true;
    private Runnable pendingAfterSplash;
    // 彩纸覆盖层（长按 logo 彩蛋）
    private ConfettiView confettiOverlay;
    // 首页问候卡片上的动态文字
    private TextView dashboardGreeting, dashboardDate, dashboardStreak;
    // 乐趣页：打卡与成就
    private TextView funStreakLabel, funDaysLabel, funCheckInLabel, funBadgesLabel, musicTipCardLabel;
    private ProgressBar funBadgeProgress;
    private LinearLayout funBadgeGrid;
    // 乐趣页：调音器（复用设备 0xA1 完整分析帧里的峰值 bin，不新增蓝牙订阅）
    private TunerGaugeView tunerGauge;
    private boolean tunerWasFresh;
    // 乐趣页：节拍器
    private TextView metronomeBpmLabel, metronomeStartButton, metronomeBeatLabel;
    private SeekBar metronomeBar;
    private final View[] metronomeBeatDots = new View[6];
    private int metronomeBpm;
    private int metronomeBeats = 4;
    private final long[] metronomeTaps = new long[5];
    private int metronomeTapCount;
    // 乐趣页：和弦库
    // （不需要保存视图列表：和弦卡片随页面构建，重建界面时旧页面整体成为垃圾。）
    // 乐趣页：音乐问答
    private TextView quizQuestionLabel, quizFeedbackLabel, quizStreakLabel;
    private final TextView[] quizOptionButtons = new TextView[4];
    private MusicTrivia.Question quizQuestion;
    private boolean quizAnswered;
    // 设置页：启动加载动画开关
    private Switch splashSwitch;

    // ==================== v3.12.0：DeepSeek AI（个人 Key / 官方通道 / 音乐搭子） ====================
    private AiCredentials aiCredentials;
    private CompanionStore companionStore;
    /** 复用一个客户端：切换配置后置空重建，避免每次对话都新建线程池。 */
    private DeepSeekClient cachedAiClient;
    private TextView aiStatusLabel, aiFunPersonaLabel, aiFunStatusLabel;
    /** AI 曲谱导入正在等待相册图片；由 onActivityResult 回填。 */
    private AiScoreImportDialog.ImageCallback pendingAiImageCallback;
    private AiScoreImportDialog.DocumentCallback pendingAiDocumentCallback;
    private Runnable pendingBrowserScoreSelected;
    /** AI 语音权限授权后要执行的动作（打开聊天等）。 */
    private Runnable pendingAiVoiceGranted;

    private final float[] wave = new float[24];
    private final int[] spectrum = new int[16];
    private int peakHz;
    private int sampleRate;
    private PowerState powerState;
    private BatteryState batteryState;
    private boolean selectedAnalysisSupported;
    private BatteryGaugeView batteryGaugeDash;
    private TextView batteryPercentDash,batteryStatusDash,batterySettings,infraredSettings;
    private ProgressBar batteryBarDash;
    private final Runnable batteryExpired=()->{batteryState=null;updateBatteryUi();};
    private TextView powerStatusDash,powerStatusSettings,powerActionDash,powerActionSettings,powerSleepButton,powerAutoValue;
    private TextView[] sleepMinuteButtons;
    private int powerTicket;
    private boolean pendingWakeAfterConnect;
    private Runnable powerTimeout;
    private final Runnable practiceLease=new Runnable(){public void run(){
        if(guideRunning && !guideDeviceReference && activityVisible && bleReady){writeCommand("PRACTICE:1");main.postDelayed(this,10000);}
    }};
    private boolean waveFresh, spectrumFresh;
    private final Runnable waveExpired = () -> {
        waveFresh = false; Arrays.fill(wave, 0); refreshVisualizer();
    };
    private final Runnable spectrumExpired = () -> {
        spectrumFresh = false; Arrays.fill(spectrum, 0); peakHz = 0;
        if (peakMetric != null) peakMetric.setText("等待数据");
        refreshVisualizer();
    };
    private TextView fullStringOrderButton;
    private String fullGuideAfterUploadId;
    private boolean guideAfterUploadRequiresFullscreen;
    private int guideUploadGeneration;
    private TextView practiceCountdownLabel;
    private TextView guideStringOrderButton;
    private android.widget.Switch fullScoringSwitch;
    private final java.util.List<SeekBar> practiceRateBars=new ArrayList<>();
    private final java.util.List<TextView> practiceRateLabels=new ArrayList<>();
    private boolean updatingPracticeRate;
    private boolean auditionJumpToPractice;
    private LedGuideFrame polyGuideFrame;
    private int guideCols;
    private int guideRow = -1;
    private int guideLevel;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) settingsModuleId = state.getString("settings_module", "");
        int storedRate=getSharedPreferences("practice_clock",0).getInt("speed",1000);
        guideSpeed=SongPlaybackState.validRate(storedRate)?storedRate/1000d:1d;
        songControlSequence=(int)(SystemClock.uptimeMillis()&65535);
        // 调色板必须在构建视图之前解析，自绘视图也会在这里取到正确颜色。
        kit = UiKit.create(this);
        settings = SettingsStore.store(this);
        scoreRepo=new ScoreRepository(this);scoreRecords=new PracticeScoreRecords(new java.io.File(getFilesDir(),"practice_records"));scoringEnabled=getSharedPreferences("practice_scoring",0).getBoolean("enabled",false);migrateLegacyScore();applyTrashRetention();lastNightMode=getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK;
        uiScale = textScaleFactor(settings.textScale());
        // 把「评分宽容」应用到判定窗口（导航视图读同一个静态值，画面与判定保持一致）
        PracticeScorer.setInputLead(PracticeScorer.leadForSetting(settings.practiceLead()));
        resolveTheme();
        AppTheme.applyWindow(this);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(pal().bgRoot));
        settingsListener = key -> onSettingChanged(key);
        settings.addListener(settingsListener);
        feedback = new Feedback(this);
        syncFeedbackSettings();
        // v3.11.0：趣味数据（连续打卡 / 成就 / 问答连对 / 节拍器偏好）与启动即打卡。
        funStore = new FunStore(this);
        journey = new PracticeJourneyStore(this);
        funStore.checkInToday();
        metronomeBpm = funStore.metronomeBpm();
        // v3.12.0：AI 凭据（个人 Key 加密存储）与音乐搭子本地数据。
        aiCredentials = new AiCredentials(this);
        companionStore = new CompanionStore(this);

        buildUi();
        setControlsEnabled(false);
        if (state != null) showPage(state.getInt("main_page", 0));
        applyKeepScreenOn();
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
        restoreLastSong();
        autoConnectIfWanted();
        // 首启欢迎弹窗延后到启动动画结束之后：否则它会盖在加载动画上，两层弹层同时出现。
        if (settings.firstRun()) { settings.setFirstRun(false); runAfterSplash(this::showWelcome); }
    }

    // ==== 调色板访问器：始终走 AppTheme，深浅主题切换后立即生效 ====
    private AppTheme.Colors pal() { return AppTheme.colors(); }
    private int cAccent() { return pal().accent; }
    private int cAccentDim() { return pal().accentDim; }
    private int cAccentSoft() { return pal().accentSoft; }
    private int cOnAccent() { return pal().bgRoot; }

    /**
     * 浅强调底（accentDim）上的前景色：暗色板下 accentDim 很深，用 accent 提亮；
     * 浅色板下 accentDim 是浅青，必须用最深的 textPrimary 才有对比度
     * （bgRoot 压在上面只有 1.29:1，textPrimary 为 12.95:1）。
     */
    private int cOnAccentDim() { return AppTheme.isLight() ? pal().textPrimary : pal().accent; }
    private int cSurface() { return pal().surface; }
    private int cSurface2() { return pal().surface2; }
    private int cSurface3() { return pal().surface3; }
    private int cSurface4() { return pal().surface4; }
    private int cTextPrimary() { return pal().textPrimary; }
    private int cTextSecondary() { return pal().textSecondary; }
    private int cTextTertiary() { return pal().textTertiary; }
    private int cStroke() { return pal().stroke; }
    private int cWarn() { return pal().warn; }
    private int cDanger() { return pal().danger; }
    private int cSuccess() { return pal().success; }

    // ==== 兼容层：旧代码里的常量名保留为方法，取值统一走调色板 ====
    private int BG() { return pal().bgRoot; }
    private int SURFACE() { return pal().surface; }
    private int SURFACE_2() { return pal().surface2; }
    private int SURFACE_3() { return pal().surface3; }
    private int TEXT() { return pal().textPrimary; }
    private int MUTED() { return pal().textSecondary; }
    private int CYAN() { return pal().accent; }
    private int CYAN_DARK() { return pal().accentDim; }
    private int AMBER() { return pal().warn; }
    private int RED() { return pal().danger; }

    // ==== 几何与排版（dp 只负责密度，sp 额外承担用户的字号档位） ====
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private int sp(float value) { return Math.round(value * uiScale); }

    /**
     * 传给 UiKit 的字号：UiKit 只按系统 scaledDensity 换算，不含应用内字号档位，
     * 所以这里先把用户档位乘进去，保证「设置 → 界面字号」对整个界面生效。
     */
    private float ts(float sizeSp) { return sizeSp * uiScale; }

    private float dpf(float value) { return value * getResources().getDisplayMetrics().density; }

    private void addContent(LinearLayout parent, View child) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(contentParams);
        parent.addView(child, lp);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }

    private GradientDrawable outlined(int color, float radius, int strokeColor) {
        GradientDrawable d = rounded(color, radius); d.setStroke(Math.max(1, dp(1)), strokeColor); return d;
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size * uiScale); v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL); v.setFontFeatureSettings("kern"); return v;
    }

    /** 小节标题（UiKit 内部字号固定，这里按用户字号档位补一次缩放）。 */
    private TextView sectionTitle(String value) {
        TextView v = kit.sectionHeader(value);
        v.setTextSize(v.getTextSize() / getResources().getDisplayMetrics().scaledDensity * uiScale);
        return v;
    }

    /** 卡片内的小标题。 */
    private TextView cardLabel(String value) { return kit.textBold(value, ts(14), cTextPrimary()); }

    private TextView hint(String value) { return kit.text(value, ts(12), cTextSecondary()); }

    /** 主要操作按钮。 */
    private TextView action(String value, int color, int textColor) {
        // 字号必须传字面量：text() 内部已经做过一次 `size * uiScale`，而 ts(x) 也是 `x * uiScale`，
        // 两者叠加会把字号平方放大（大档 16.24sp → 18.84sp，小档反而偏小 12%）。
        // 行数不再设上限：父容器已改 wrap content，长标签会换行而不是被硬截断。
        TextView v = text(value, 14, textColor);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setMinHeight(dp(46));
        v.setPadding(dp(12), dp(8), dp(12), dp(8));
        v.setBackground(kit.ripple(color, 14));
        v.setClickable(true); v.setFocusable(true);
        return v;
    }

    private TextView primaryAction(String label) { return action(label, cAccent(), cOnAccent()); }

    /** 强调但非最高优先级的操作：浅强调底 + 高对比前景（浅色主题下自动改用深色文字）。 */
    private TextView tinta(String label) { return action(label, cAccentDim(), cOnAccentDim()); }

    /** 灯阵预览的 50ms 自刷新只在界面前台且灯阵非“关闭”时运行，避免后台空转耗电。 */
    private void setLedAnimation(boolean visible) {
        if (ledPreview == null) return;
        ledPreview.setAnimating(visible && selectedLedMode != 0 && !ledAnimationPaused);
    }

    /** 灯阵预览：数据未变时也要重绘（参数在视图外部）。 */
    private void invalidateLedPreview() { if (ledPreview != null) { if(bitmapState!=null)ledPreview.setCustomProfile(bitmapState.profile.data); ledPreview.setCapo(boardCapo()); ledPreview.setParams(selectedLedMode, selectedLedAxis, ledSpeed, ledBrightness, ledFlags, ledLandscape); setLedAnimation(activityVisible); ledPreview.invalidate(); } }

    private TextView ghostAction(String label) { return action(label, cSurface3(), cTextPrimary()); }

    private TextView outlineAction(String label) {
        // 同 action()：字号用字面量（避免与 text() 内的 uiScale 相乘两次）；不限行数。
        TextView v = text(label, 14, cTextSecondary());
        v.setGravity(Gravity.CENTER); v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setMinHeight(dp(46)); v.setPadding(dp(12), dp(8), dp(12), dp(8));
        v.setBackground(kit.ripple(cSurface2(), 14));
        v.setClickable(true); v.setFocusable(true);
        return v;
    }

    private TextView chip(String value, boolean selected) { return scaleText(kit.chip(value, selected)); }

    private void setChip(TextView v, boolean selected) { kit.styleChip(v, selected); }

    /** 分段控件单段。 */
    private TextView segment(String value, boolean selected) { return scaleText(kit.segment(value, selected)); }

    /** 分段控件单段（连体外观：首尾外圆角、相邻内侧小圆角）。 */
    private TextView segment(String value, boolean selected, int index, int count) { return scaleText(kit.segment(value, selected)); }

    /**
     * UiKit 的 chip/segment/badge 等组件内部字号固定（签名已冻结），无法传入字号。
     * 这里在拿到控件后按用户字号档位补一次缩放：px → sp → 乘档位 → px。
     */
    private TextView scaleText(TextView v) {
        if (v == null || uiScale == 1f) return v;
        float density = getResources().getDisplayMetrics().scaledDensity;
        if (density <= 0f) return v;
        float sp = v.getTextSize() / density;
        v.setTextSize(sp * uiScale);
        return v;
    }

    /** 胶囊徽标（字号随档位缩放）。 */
    private TextView badge(String value, int bgColor, int textColor) { return scaleText(kit.badge(value, bgColor, textColor)); }

    private void setSegment(TextView v, boolean selected) { kit.styleSegment(v, selected); }

    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v;
    }

    /** 卡片容器：圆角背景 + 统一内边距。 */
    private LinearLayout card() {
        LinearLayout v = column();
        v.setPadding(dp(16), dp(14), dp(16), dp(14));
        v.setBackground(kit.card());
        return v;
    }

    private View page(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(pal().bgRoot);
        scroll.setClipToPadding(false);
        scroll.addView(content);
        return scroll;
    }

    private void addSpace(LinearLayout parent, int width) { Space s = new Space(this); parent.addView(s, new LinearLayout.LayoutParams(dp(width), 1)); }

    private void addGap(LinearLayout parent, int height) { Space s = new Space(this); parent.addView(s, new LinearLayout.LayoutParams(1, dp(height))); }

    /**
     * 给「按钮行」设最小高度、并把行高改为 wrap content。
     *
     * 这些行原来用固定 dp 高度（38~46dp），父容器会把随字号变高的按钮夹回去 ——
     * 这也是「文字被遮挡」的来源之一。改成 wrap content 后按钮可自然变高；
     * 同时把原数值保留为**最小高度**，所以默认字号下观感与改动前一致，
     * 不会出现行变矮、点击区缩水。
     */
    private void rowMinHeight(LinearLayout row, int heightDp) {
        row.setMinimumHeight(dp(heightDp));
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp != null) { lp.height = -2; row.setLayoutParams(lp); }
    }

    /** 指标小卡：上方说明 + 下方数值。 */
    private LinearLayout metricBox(String name, String value, TextView[] valueOut) {
        LinearLayout box = column();
        box.setPadding(dp(13), dp(10), dp(13), dp(10));
        box.setBackground(kit.card());
        // 最小高度而非固定高度：数值 20sp 在大字号档位下会变高，固定高度会裁字。
        box.setMinimumHeight(dp(66));
        box.addView(kit.text(name, ts(11), cTextTertiary()));
        TextView v = kit.textBold(value, ts(20), cTextPrimary());
        v.setPadding(0, dp(2), 0, 0);
        box.addView(v);
        valueOut[0] = v;
        return box;
    }

    /** 顶部连接状态徽标：圆点 + 文本。文案较长时省略号截断，避免挤压标题。 */
    private LinearLayout statusBadge(String initial) {
        LinearLayout pill = row();
        pill.setPadding(dp(10), 0, dp(12), 0);
        pill.setBackground(kit.bg(cSurface2(), 999));
        statusDot = new View(this);
        statusDot.setBackground(kit.bg(cTextTertiary(), 999));
        pill.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        statusText = kit.text(initial, ts(12), cTextSecondary());
        statusText.setPadding(dp(7), 0, 0, 0);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pill.addView(statusText);
        // 宽度上限 + 最小高度：大字号下胶囊可以变高，文字过长由 ellipsize 收尾，
        // 而不是被固定 30dp 高度裁掉。
        pill.setMinimumHeight(dp(30));
        pill.setLayoutParams(new LinearLayout.LayoutParams(dp(172), -2));
        return pill;
    }

    private void statusDotColor(int color) { if (statusDot != null) statusDot.setBackground(kit.bg(color, 999)); }

    /** 统一的状态文本入口：同时刷新顶部徽标圆点颜色。 */
    private void setStatusText(String value) {
        if (statusText != null) statusText.setText(value);
        if (statusDot == null || statusReliable) return;
        if (bleReady) statusDotColor(cSuccess());
        else if (scanning || (gatt != null && !bleReady)) statusDotColor(cWarn());
        else statusDotColor(cTextTertiary());
    }

    /** 设备回读确认的连接状态：绿点。 */
    private void setConnectionStatus(String value) { statusDotColor(cSuccess()); if (statusText != null) statusText.setText(value); }

    /** 失败状态：红点。 */
    private void setFailureStatus(String value) { statusDotColor(cDanger()); statusReliable = true; if (statusText != null) statusText.setText(value); }

    // ==== 主题 ====
    private void resolveTheme() {
        String mode = settings == null ? SettingsStore.THEME_DARK : settings.themeMode();
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        AppTheme.applyStyle(AppTheme.Style.fromId(mode), mask == Configuration.UI_MODE_NIGHT_YES);
    }

    private static float textScaleFactor(int scale) {
        if (scale == SettingsStore.TEXT_SCALE_SMALL) return 0.88f;
        if (scale == SettingsStore.TEXT_SCALE_LARGE) return 1.16f;
        return 1f;
    }

    private void applyWindowTheme() { AppTheme.applyWindow(this); }

    /** 设置变化：需要重建界面的属性就地重建，其余即时生效。 */
    private void onSettingChanged(String key) {
        if (SettingsStore.KEY_THEME_MODE.equals(key) || SettingsStore.KEY_TEXT_SCALE.equals(key)) {
            rebuildUi();
            return;
        }
        if (SettingsStore.KEY_KEEP_SCREEN_ON.equals(key)) applyKeepScreenOn();
        if (SettingsStore.KEY_GUIDE_LANDING_RIGHT.equals(key)||SettingsStore.KEY_GUIDE_STRINGS_REVERSED.equals(key)) updateGuideDirection();
        // 音符显示方式：既要重算音符文本，也要同步三选一控件的选中态，
        // 否则会出现「显示方式变了、高亮还停在原来那段」。
        if (SettingsStore.KEY_SHOW_FINGERING.equals(key) || SettingsStore.KEY_NOTE_STYLE.equals(key)) {
            updateNoteLabels();
            refreshNoteStyleButtons();
        }
        if (SettingsStore.KEY_HAPTICS_ENABLED.equals(key) || SettingsStore.KEY_SOUND_ENABLED.equals(key)) syncFeedbackSettings();
        // 试听音量：播放中改滑杆要立即作用到 AudioTrack，并同步另一处滑杆的显示。
        if (SettingsStore.KEY_PREVIEW_VOLUME.equals(key)) applyAuditionVolume();
        if (visualizer != null) visualizer.invalidate();
        if (ledPreview != null) ledPreview.invalidate();
        if (songGuideView != null) songGuideView.invalidate();
        refreshSettingsOverview();
    }

    /** 重建整个界面（主题/字号切换）。 */
    private void rebuildUi() {
        // 重建界面会把「乐趣」页的节拍器 UI 换成一套新实例，但引擎还在跑；
        // 先停掉再重建，避免出现“声音在响、按钮却显示未开始”的错位。
        stopMetronome();
        auditionLabels.clear();
        auditionVolumeBars.clear();
        auditionVolumeLabels.clear();
        // 注意：这里**不**调用 disconnect()。界面重建只是换一套 View，
        // GATT 回调全部通过字段引用当前视图，因此连接可以原样保留——
        // 改主题/字号不应该把蓝牙断掉。
        int previousPage = currentPage;
        uiScale = textScaleFactor(settings.textScale());
        resolveTheme();
        applyWindowTheme();
        kit = UiKit.create(this);
        librarySongs.clear();
        libraryRows.clear();
        currentNote = "--"; guideNote = "--";
        buildUi();
        setControlsEnabled(bleReady);
        showPage(previousPage);
        applyKeepScreenOn();
        // 重建后 buildSongPage 会用曲库第一首做程序化选中；这里再恢复用户真正选中的曲目，
        // 避免切换主题/字号把用户选到一半的曲目重置掉。
        restoreLastSong();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        int night=config.uiMode&Configuration.UI_MODE_NIGHT_MASK;boolean changed=night!=lastNightMode;lastNightMode=night;if(changed&&fullScreenDialog==null&&settings!=null&&SettingsStore.THEME_SYSTEM.equals(settings.themeMode()))rebuildUi();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("main_page", currentPage);
        state.putString("settings_module", settingsModuleId);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (currentPage == PAGE_SETTINGS && settingsNavigator != null && settingsNavigator.goBack()) return;
        super.onBackPressed();
    }

    // ==== 设置联动 ====
    private void syncFeedbackSettings() {
        if (feedback == null || settings == null) return;
        feedback.setHapticsEnabled(settings.hapticsEnabled());
        feedback.setSoundEnabled(settings.soundEnabled());
    }

    private void tap() { if (feedback != null) feedback.tap(); }
    private void confirmFeedback() { if (feedback != null) feedback.confirm(); }
    private void warnFeedback() { if (feedback != null) feedback.warn(); }

    private void applyKeepScreenOn() {
        boolean on = settings != null && settings.keepScreenOn();
        if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showWelcome() {
        AppDialogs.message(this, "欢迎使用 Ukulele Studio",
            "已按新版界面重新排版，原有演奏、灯阵与曲谱功能保持不变。\n\n"
            + "「设置」页可以调整深浅主题、界面字号、音符显示方式、震动反馈、启动动画与默认连接。\n\n"
            + "「曲谱」页新增「我的曲库」入口，导入的照片、PDF 与 MIDI 都集中在那里管理。\n\n"
            + "底部新增「乐趣」页：调音器、节拍器、和弦试听、音乐问答与成就打卡都在那里。\n\n"
            + "彩蛋：长按左上角 Logo 试试。",
            "开始使用", null, null, null, null, null);
    }

    private void buildUi() {
        // 重建界面会产生全新的导航 View，必须清空选中缓存，否则 updateNavSelection 会提前返回、
        // 新底栏没有任何一项高亮（主题/字号切换后常见）。
        navSelectedPage = -1;
        LinearLayout root = column();
        root.setBackgroundColor(pal().bgRoot);
        rootView = root;

        LinearLayout header = row();
        header.setPadding(dp(20), dp(14), dp(20), dp(10));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_ukulele_logo);
        header.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        // 彩蛋：长按 logo 撒彩纸 + 解锁「彩蛋猎人」徽章（纯手机侧动画，不影响任何设备状态）。
        logo.setOnLongClickListener(v -> { showEasterEgg(); return true; });
        LinearLayout heading = column();
        heading.setPadding(dp(12), 0, dp(8), 0);
        headerTitle = kit.textBold("演奏台", ts(20), cTextPrimary());
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headerSubtitle = kit.text("实时声音与演奏状态", ts(12), cTextSecondary());
        headerSubtitle.setSingleLine(true);
        headerSubtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(headerTitle);
        heading.addView(headerSubtitle);
        // 用 minHeight 而不是固定 46dp：用户把「界面字号」调大后标题/副标题会变高，
        // 固定高度会把第二行文字裁掉。
        heading.setMinimumHeight(dp(46));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(statusBadge("未连接"));
        // 同理：顶栏高度改为最小高度，字号放大时整栏可以变高而不是裁字。
        header.setMinimumHeight(dp(66));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        statusDotColor(cTextTertiary());

        pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        pages[0] = buildDashboardPage();
        pages[1] = buildLedPage();
        pages[2] = buildSongPage();
        pages[3] = buildSongPracticePage();
        // 注意：pages[] 的下标必须与底栏 labels[]、showPage() 的 titles[]/subs[] 完全对齐。
        // v3.11.0 顺序为：0 演奏台 / 1 灯阵 / 2 曲库 / 3 练习 / 4 乐趣 / 5 设置。
        pages[PAGE_FUN] = buildFunPage();
        pages[PAGE_SETTINGS] = buildSettingsPage();

        // Bind the initial song only after both library and practice views exist.
        // 进入曲谱页默认选中第一首（与基线一致）。用 programmaticSelection 抑制写入
        // “上次曲目”，否则这次程序化选中会覆盖掉真正的历史记录。
        if (!librarySongs.isEmpty()) {
            programmaticSelection = true;
            try { selectLibrarySong(librarySongs.get(0)); }
            finally { programmaticSelection = false; }
        }
        updateSongPlayState();


        LinearLayout nav = row();
        nav.setPadding(dp(8), dp(6), dp(8), dp(8));
        nav.setBackgroundColor(pal().surface);
        // 「曲库」与「练习」拆成两个入口：找谱和练谱是两件事，不再挤在一个页面里切换标签。
        // v3.11.0 增加「乐趣」：调音器、节拍器、和弦库、音乐问答与成就打卡都在那里。
        String[] labels = {"演奏台", "灯阵", "曲库", "练习", "乐趣", "设置"};
        int[] icons = {R.drawable.ic_dashboard, R.drawable.ic_led, R.drawable.ic_music, R.drawable.ic_play, R.drawable.ic_sparkle, R.drawable.ic_settings};
        for (int i = 0; i < labels.length; ++i) {
            final int index = i;
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(2), dp(3), dp(2), dp(3));
            item.setClickable(true); item.setFocusable(true);
            final int pageIndex = i;
            View.OnClickListener listener = v -> {
                if (pageIndex == PAGE_SETTINGS && currentPage == PAGE_SETTINGS && settingsNavigator != null && settingsNavigator.goBack()) return;
                tap(); showPage(pageIndex);
            };
            item.setOnClickListener(listener);
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(cTextTertiary());
            // 无障碍：整项已有文字标签，图标不应被重复朗读；把可读名称放到可点击的 item 上。
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            item.setContentDescription(labels[i]);
            TextView label = kit.text(labels[i], ts(11), cTextTertiary());
            label.setGravity(Gravity.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(-1, dp(24)));
            // 导航标签用最小高度：字号档位调到「大」时 11sp→12.76sp，固定 18dp 会把文字裁掉。
            label.setMinimumHeight(dp(18));
            item.addView(label, new LinearLayout.LayoutParams(-1, -2));
            // 导航项/底栏改为最小高度 + wrap content，字号放大时不会裁字。
            item.setMinimumHeight(dp(54));
            navItems[i] = label; navIcons[i] = icon; navContainers[i] = item;
            nav.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        nav.setMinimumHeight(dp(68));
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        // 外壳：主线界面 + 可选启动动画/彩纸覆盖层。用 FrameLayout 叠加，不影响原有布局测量。
        appShell = new FrameLayout(this);
        appShell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(appShell);
        showPage(0);updatePowerUi();
        // 启动动画的「是否播放」只在进程内第一次构建界面时判断一次。
        // 不能只靠“已经展示过”的标记：若本次冷启动时开关是关的，该标记不会置位，
        // 之后在设置里打开开关再切主题/字号，就会命中条件把 2.1s 启动动画重播一遍（复核 P2-1）。
        boolean firstBuild = firstUiBuild;
        firstUiBuild = false;
        if (firstBuild && settings != null && settings.splashEnabled()) startSplashAnimation();
    }

    private void showPage(int page) {
        if (page < 0 || page >= PAGE_COUNT || pages[page] == null) return;
        int previous = currentPage;
        if(previous==3&&page!=3&&!(fullCountdown.active()&&fullCountdownDevice)&&fullGuideAfterUploadId==null){cancelFullCountdown();guideUploadGeneration++;}
        // 离开乐趣页时停止节拍器：避免切页后声音还在继续却看不到控制按钮。
        if (previous == PAGE_FUN && page != PAGE_FUN) stopMetronome();
        currentPage = page;
        requestAnalysisMode();
        pageHost.removeAllViews();
        View target = pages[page];
        pageHost.addView(target, new FrameLayout.LayoutParams(-1, -1));
        // 页面切换的轻量入场动画：新页面淡入并略微上移，比硬切更有“换台”感。
        target.animate().cancel();
        if (previous != page) {
            target.setAlpha(0f);
            target.setTranslationY(dp(10));
            target.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else {
            target.setAlpha(1f);
            target.setTranslationY(0f);
        }
        String[] titles = {"演奏台", "灯阵", "曲库", "练习", "乐趣", "设置"};
        String[] subs = {"实时音频分析", "灯光与场景", "曲谱管理", "跟弹·试听·评分", "调音·节拍·问答", "设备与偏好"};
        headerTitle.setText(titles[page]); headerSubtitle.setText(subs[page]);
        updateNavSelection(page);
        if (page == PAGE_SETTINGS) updateDeviceInfo();
        if (page == 0) refreshDashboardHero();
        if (page == PAGE_FUN) refreshFunPage();
        // 练习页顶部摘要可能因选曲/上传/设备状态变化而变，每次进入都重新同步。
        if (page == 3) refreshPracticeSummary();
        // AI 状态可能因配置/聊天人格变化；进入设置或乐趣页时刷新显示。
        if (page == PAGE_SETTINGS || page == PAGE_FUN) refreshAiUi();
        // 调音器只在乐趣页可见时吃数据；离开时主动置为空闲，避免不可见视图继续自刷新。
        if (tunerGauge != null && page != PAGE_FUN) tunerGauge.setIdle(true);
    }

    /** 底部导航选中态：颜色 + accentSoft 圆角底衬 + 图标轻微回弹。只影响外观。 */
    private void updateNavSelection(int page) {
        if (navSelectedPage == page) return;
        navSelectedPage = page;
        for (int i = 0; i < navItems.length; ++i) {
            if (navItems[i] == null) continue;
            boolean active = i == page;
            navItems[i].setTextColor(active ? cAccent() : cTextTertiary());
            if (navIcons[i] != null) {
                navIcons[i].setColorFilter(active ? cAccent() : cTextTertiary());
                if (active) {
                    navIcons[i].setScaleX(1.12f); navIcons[i].setScaleY(1.12f);
                    navIcons[i].animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                }
            }
            if (navContainers[i] != null) {
                navContainers[i].setBackground(active ? kit.bg(cAccentSoft(), 14) : null);
            }
        }
    }

    private View buildDashboardPage() {
        LinearLayout body = column();
        body.setPadding(dp(20), dp(4), dp(20), dp(24));

        addContent(body,buildDashboardBattery());
        addContent(body, buildDashboardHero());
        addContent(body,buildPowerCard(true));

        // 波形 + 频谱
        LinearLayout waveCard = card();
        LinearLayout waveHead = row();
        TextView waveTitle = cardLabel("实时音频分析"); waveTitle.setMinimumHeight(dp(28));
        waveHead.addView(waveTitle, new LinearLayout.LayoutParams(0, -2, 1));
        waveHead.addView(badge("FPGA", cAccentSoft(), cAccent()));
        waveCard.addView(waveHead);
        visualizer = new AudioScopeView(this);visualizer.setMode(audioDisplayMode);if(analysisFresh)visualizer.update(audioAnalysis);
        FrameLayout waveBox = new FrameLayout(this);
        waveBox.setBackground(kit.bg(cSurface2(), 14));
        waveBox.addView(visualizer, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams waveLp = new LinearLayout.LayoutParams(-1, dp(280));
        waveLp.topMargin = dp(12);
        waveCard.addView(waveBox, waveLp);
        addAudioControls(waveCard);
        addContent(body, waveCard);

        // 指标 2×2：数值 View 由 out 参数直接取回，不再依赖子视图下标
        LinearLayout metricRow1 = row();
        TextView[] out1 = new TextView[1];
        TextView[] out2 = new TextView[1];
        metricRow1.addView(metricBox("峰值频率", "-- Hz", out1), new LinearLayout.LayoutParams(0, -2, 1));
        peakMetric = out1[0];
        addSpace(metricRow1, 12);
        metricRow1.addView(metricBox("采样率", "-- Hz", out2), new LinearLayout.LayoutParams(0, -2, 1));
        sampleMetric = out2[0];
        addContent(body, metricRow1);
        LinearLayout metricRow2 = row();
        TextView[] out3 = new TextView[1];
        TextView[] out4 = new TextView[1];
        metricRow2.addView(metricBox("当前音符", "--", out3), new LinearLayout.LayoutParams(0, -2, 1));
        noteMetric = out3[0];
        addSpace(metricRow2, 12);
        metricRow2.addView(metricBox("指引行", "等待", out4), new LinearLayout.LayoutParams(0, -2, 1));
        guideMetric = out4[0];
        addContent(body, metricRow2);
        takeCard = buildTakeCard();
        addContent(body, takeCard);

        body.addView(sectionTitle("演奏控制"));

        LinearLayout modeCard = card();
        modeCard.addView(cardLabel("演奏模式"));
        modeCard.addView(hint("切换前请确认 FPGA 已在线，切换结果以设备回读为准。"));
        LinearLayout modeRow = row();
        modeLockButton = segment("一拍锁定", false);
        modePolyButton = segment("四弦复音", false);
        modeRow.addView(modeLockButton, new LinearLayout.LayoutParams(0, -2, 1));
        addSpace(modeRow, 10);
        modeRow.addView(modePolyButton, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(-1, -2);
        modeLp.topMargin = dp(12);
        rowMinHeight(modeRow, 42); modeCard.addView(modeRow, modeLp);
        modeStatusText = hint("等待 FPGA 实际模式回读");
        modeStatusText.setPadding(0, dp(10), 0, 0);
        modeCard.addView(modeStatusText);
        modeLockButton.setOnClickListener(v -> selectPlayMode(0));
        modePolyButton.setOnClickListener(v -> selectPlayMode(1));
        addContent(body, modeCard);

        LinearLayout volCard = card();
        LinearLayout volRow = row();
        volRow.addView(cardLabel("音量"), new LinearLayout.LayoutParams(0, -2, 1));
        volumeValue = text("20", 20, cAccent());
        volumeValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        volumeValue.setGravity(Gravity.CENTER);
        // 宽度留够 3 位数（100），高度用最小高度：大字号下数值不会被裁。
        volumeValue.setMinWidth(dp(56));
        volumeValue.setMinimumHeight(dp(30));
        volRow.addView(volumeValue, new LinearLayout.LayoutParams(-2, -2));
        volCard.addView(volRow);
        SeekBar volume = kit.seekBar(cAccent());
        volume.setMax(100); volume.setProgress(20);
        volumeBar = volume;
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar b, int p, boolean fromUser) { volumeValue.setText(String.valueOf(p)); if (fromUser) { currentVolume = p; writeCommand("VOLUME:" + p); } } public void onStartTrackingTouch(SeekBar b) { volumeDragging = true; } public void onStopTrackingTouch(SeekBar b) { volumeDragging = false; writeCommand("VOLUME:" + b.getProgress()); } });
        volCard.addView(volume);
        addContent(body, volCard);

        LinearLayout toneCard = card();
        toneCard.addView(cardLabel("音色"));
        addModelControls(toneCard);addContent(body, toneCard);
        addChainControls(body);

        // 音乐冷知识：纯手机侧内容，离线也有变化，让首页在没连琴时也不那么“空”。
        body.addView(sectionTitle("音乐冷知识"));
        LinearLayout tipCard = card();
        LinearLayout tipHead = row();
        TextView tipTitle = cardLabel("今天聊点音乐"); tipTitle.setMinimumHeight(dp(28));
        tipHead.addView(tipTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView tipRefresh = outlineAction("换一条");
        tipRefresh.setMinimumWidth(dp(88));
        tipHead.addView(tipRefresh);
        tipCard.addView(tipHead);
        musicTipCardLabel = kit.text(MusicTrivia.randomTip(funRandom), ts(13), cTextSecondary());
        musicTipCardLabel.setLineSpacing(dp(4), 1f);
        musicTipCardLabel.setPadding(0, dp(8), 0, 0);
        tipCard.addView(musicTipCardLabel);
        tipRefresh.setOnClickListener(v -> { tap(); musicTipCardLabel.setText(MusicTrivia.randomTip(funRandom)); });
        addContent(body, tipCard);

        refreshDashboardHero();
        return page(body);
    }

    /**
     * 首页问候卡：渐变底 + 时段问候 + 连续打卡 + 两个一键入口。
     * 只读取本地 FunStore 与系统时间，不触碰蓝牙和任何原有控制状态。
     */
    private LinearLayout buildDashboardHero() {
        LinearLayout hero = column();
        hero.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable bg = kit.gradient(cAccentSoft(), cSurface(), 18);
        bg.setStroke(Math.max(1, dp(1)), cStroke());
        hero.setBackground(bg);

        LinearLayout top = row();
        LinearLayout texts = column();
        dashboardGreeting = kit.textBold("你好", ts(20), cTextPrimary());
        dashboardGreeting.setSingleLine(true);
        dashboardGreeting.setEllipsize(android.text.TextUtils.TruncateAt.END);
        dashboardDate = kit.text("", ts(12), cTextSecondary());
        dashboardDate.setPadding(0, dp(3), 0, 0);
        texts.addView(dashboardGreeting);
        texts.addView(dashboardDate);
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        View stamp=new com.ukulele.monitor.ui.ThemeStamp(this);
        LinearLayout.LayoutParams stampSize=new LinearLayout.LayoutParams(dp(46),dp(46));
        stampSize.leftMargin=dp(8);top.addView(stamp,stampSize);
        hero.addView(top);

        dashboardStreak = badge("连续 0 天", cAccentSoft(), cAccent());
        LinearLayout.LayoutParams streakLp = new LinearLayout.LayoutParams(-2, -2);
        streakLp.topMargin = dp(12);
        hero.addView(dashboardStreak, streakLp);

        LinearLayout actions = row();
        TextView practice = primaryAction("开始今日练习");
        practice.setOnClickListener(v -> { tap(); showPage(3); });
        TextView explore = tinta("探索乐趣");
        explore.setOnClickListener(v -> { tap(); showPage(PAGE_FUN); });
        actions.addView(practice, new LinearLayout.LayoutParams(0, -2, 1));
        addSpace(actions, 10);
        actions.addView(explore, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-1, -2);
        actionLp.topMargin = dp(14);
        rowMinHeight(actions, 46);
        hero.addView(actions, actionLp);
        return hero;
    }

    /** 刷新首页问候卡上的时段问候、日期与打卡信息。页面未构建时静默跳过。 */
    private void refreshDashboardHero() {
        if (dashboardGreeting == null || funStore == null) return;
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String greeting = hour < 6 ? "夜深了，来一段轻轻的旋律"
            : hour < 12 ? "早上好，今天也弹一首吧"
            : hour < 14 ? "中午好，休息一下弹两下"
            : hour < 18 ? "下午好，练一段新旋律"
            : "晚上好，用琴声收个尾";
        dashboardGreeting.setText(greeting);
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("M月d日 EEEE", java.util.Locale.CHINA);
        dashboardDate.setText(format.format(new java.util.Date()) + " · 累计打卡 " + funStore.totalDays() + " 天");
        if (funStore.streak() > 0) {
            dashboardStreak.setText("连续打卡 " + funStore.streak() + " 天");
            dashboardStreak.setBackground(kit.bg(cAccentSoft(), 999));
            dashboardStreak.setTextColor(cAccent());
        } else {
            dashboardStreak.setText("今天还没打卡");
            dashboardStreak.setBackground(kit.bg(cSurface3(), 999));
            dashboardStreak.setTextColor(cTextSecondary());
        }
    }

    private View buildLedPage() {
        LinearLayout body = column();
        body.setPadding(dp(20), dp(4), dp(20), dp(24));

        LinearLayout tools = row();
        ledNoteLabel = kit.textBold("演奏音符  --", ts(24), cTextPrimary());
        tools.addView(ledNoteLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView rotate = outlineAction("↻ 横向");
        // 高度改最小高度：文字随字号档位放大后（最大 16.24sp）单行已需约 41dp，
        // 固定 40dp 会把文字裁掉。
        rotate.setMinimumHeight(dp(40));
        tools.addView(rotate, new LinearLayout.LayoutParams(dp(96), -2));
        // 横向 / 竖向切换：同时刷新按钮文案与预览（必须走 invalidateLedPreview，才能把新的 ledLandscape 传给视图）
        rotate.setOnClickListener(v -> {
            tap();
            ledLandscape = !ledLandscape;
            rotate.setText(ledLandscape ? "↻ 横向" : "↻ 竖向");
            invalidateLedPreview();
        });
        addContent(body, tools);
        TextView fullLed=tinta("全屏灯阵");fullLed.setOnClickListener(v->openFullScreen(ledPreview,false));addContent(body,fullLed);

        ledPreview = new LedPreviewView(this);
        FrameLayout previewBox = new FrameLayout(this);
        previewBox.setBackground(kit.bg(cSurface2(), 16));
        previewBox.addView(ledPreview, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, dp(352));
        previewLp.bottomMargin = dp(contentParams);
        body.addView(previewBox, previewLp);

        LinearLayout customCard=card();
        TextView customTitle=cardLabel("自定义72灯");customCard.addView(customTitle);
        bitmapStatusText=kit.text("可离线编辑图案，连接后应用",ts(13),cTextSecondary());customCard.addView(bitmapStatusText);
        TextView editBitmap=tinta("编辑72灯 · 亮度与呼吸");editBitmap.setOnClickListener(v->openBitmapEditor());
        LinearLayout.LayoutParams editBitmapLp=new LinearLayout.LayoutParams(-1,-2);editBitmapLp.topMargin=dp(10);customCard.addView(editBitmap,editBitmapLp);addContent(body,customCard);

        // 灯效场景：把“模式 + 方向 + 速度 + 亮度”打包成一键组合，仍然是同一条 LED 指令，
        // 应用后下面的滑块/模式按钮会同步到真实值，可以继续手动微调。
        body.addView(sectionTitle("灯效场景"));
        LinearLayout sceneCard = card();
        sceneCard.addView(hint("一键切换整套灯效；应用后仍可在下方继续微调。"));
        GridLayout sceneGrid = new GridLayout(this);
        sceneGrid.setColumnCount(2);
        sceneGrid.setUseDefaultMargins(false);
        String[] sceneNames = {"湖畔呼吸", "星河游龙", "涟漪扩散", "拨弦闪光"};
        String[] sceneDescs = {"柔和呼吸 · 低亮度", "游龙快移 · 列方向", "波纹中速 · 全亮", "拨弦即闪 · 高亮"};
        final int[][] sceneParams = {{2, 0, 18, 140}, {4, 1, 72, 200}, {5, 0, 40, 180}, {6, 0, 24, 220}};
        for (int i = 0; i < sceneNames.length; ++i) {
            final int sceneIndex = i;
            LinearLayout cell = column();
            cell.setPadding(dp(12), dp(11), dp(12), dp(11));
            cell.setBackground(kit.ripple(cSurface2(), 14));
            cell.setClickable(true); cell.setFocusable(true);
            TextView title = kit.textBold(sceneNames[i], ts(14), cTextPrimary());
            TextView desc = kit.text(sceneDescs[i], ts(11), cTextSecondary());
            desc.setPadding(0, dp(3), 0, 0);
            cell.addView(title);
            cell.addView(desc);
            cell.setOnClickListener(v -> applyLedScene(sceneParams[sceneIndex]));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0; lp.height = -2;
            lp.columnSpec = GridLayout.spec(i % 2, 1, 1);
            lp.rowSpec = GridLayout.spec(i / 2);
            lp.setMargins(0, dp(10), i % 2 == 0 ? dp(10) : 0, 0);
            sceneGrid.addView(cell, lp);
        }
        sceneCard.addView(sceneGrid);
        // AI 灯效导演：用一句话描述氛围，让 DeepSeek 给出模式/方向/速度/亮度参数；
        // 只复用既有 applyLedScene 与 LED 指令，不新增设备协议。
        TextView aiLight = outlineAction("AI 灯效导演");
        aiLight.setOnClickListener(v -> { tap(); openAiLightDirector(); });
        LinearLayout.LayoutParams aiLightLp = new LinearLayout.LayoutParams(-1, -2);
        aiLightLp.topMargin = dp(10);
        sceneCard.addView(aiLight, aiLightLp);
        addContent(body, sceneCard);

        body.addView(sectionTitle("灯光模式"));
        GridLayout modes = new GridLayout(this);
        modes.setColumnCount(2);
        modes.setUseDefaultMargins(false);
        String[] names = {"关闭", "常亮", "全亮呼吸", "行/列渐进", "游龙", "波纹", "拨弦闪烁"};
        TextView[] buttons = new TextView[names.length];
        ledModeButtons = buttons;
        for (int i = 0; i < names.length; ++i) {
            final int index = i;
            buttons[i] = segment(names[i], i == 0);
            buttons[i].setOnClickListener(v -> { tap(); selectedLedMode = index; for (int j = 0; j < buttons.length; ++j) setSegment(buttons[j], j == selectedLedMode); applyLed(); invalidateLedPreview(); });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0; lp.height = -2;
            lp.columnSpec = GridLayout.spec(i % 2, 1, 1);
            lp.rowSpec = GridLayout.spec(i / 2);
            lp.setMargins(0, dp(4), i % 2 == 0 ? dp(8) : 0, dp(4));
            modes.addView(buttons[i], lp);
        }
        addContent(body, modes);

        body.addView(sectionTitle("方向与参数"));
        LinearLayout controls = card();

        LinearLayout axisRow = row();
        TextView rowAxis = segment("行方向", true);
        TextView colAxis = segment("列方向", false);
        rowAxisButton = rowAxis; colAxisButton = colAxis;
        axisRow.addView(rowAxis, new LinearLayout.LayoutParams(0, -2, 1));
        addSpace(axisRow, 10);
        axisRow.addView(colAxis, new LinearLayout.LayoutParams(0, -2, 1));
        rowAxis.setOnClickListener(v -> { tap(); selectedLedAxis = 0; setSegment(rowAxis, true); setSegment(colAxis, false); applyLed(); invalidateLedPreview(); });
        colAxis.setOnClickListener(v -> { tap(); selectedLedAxis = 1; setSegment(rowAxis, false); setSegment(colAxis, true); applyLed(); invalidateLedPreview(); });
        rowMinHeight(axisRow, 42);
        controls.addView(axisRow);

        LinearLayout speedHead = row();
        speedHead.setPadding(0, dp(16), 0, 0);
        TextView speedTitle = hint("流动速度"); speedTitle.setMinimumHeight(dp(24));
        speedHead.addView(speedTitle, new LinearLayout.LayoutParams(0, -2, 1));
        speedValue = kit.textBold("24", ts(14), cWarn());
        speedHead.addView(speedValue);
        controls.addView(speedHead);
        SeekBar speed = kit.seekBar(cWarn());
        speed.setMax(255); speed.setProgress(24);
        speedBar = speed;
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar b, int p, boolean fromUser) { ledSpeed = p; if (fromUser) ledDraftDirty = true; speedValue.setText(String.valueOf(p)); } public void onStartTrackingTouch(SeekBar b) { ledDraftDirty = true; } public void onStopTrackingTouch(SeekBar b) { applyLed(); } });
        controls.addView(speed);

        LinearLayout brightHead = row();
        brightHead.setPadding(0, dp(8), 0, 0);
        TextView brightTitle = hint("整体亮度"); brightTitle.setMinimumHeight(dp(24));
        brightHead.addView(brightTitle, new LinearLayout.LayoutParams(0, -2, 1));
        brightnessValue = kit.textBold("180", ts(14), cAccent());
        brightHead.addView(brightnessValue);
        controls.addView(brightHead);
        SeekBar bright = kit.seekBar(cAccent());
        bright.setMax(255); bright.setProgress(180);
        brightnessBar = bright;
        bright.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar b, int p, boolean fromUser) { ledBrightness = p; if (fromUser) ledDraftDirty = true; brightnessValue.setText(String.valueOf(p)); } public void onStartTrackingTouch(SeekBar b) { ledDraftDirty = true; } public void onStopTrackingTouch(SeekBar b) { applyLed(); } });
        controls.addView(bright);

        TextView apply = primaryAction("应用灯阵");
        ledApplyButton = apply;
        apply.setOnClickListener(v -> { tap(); applyLed(); });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = dp(14);
        controls.addView(apply, ap);
        addContent(body, controls);

        return page(body);
    }

    /**
     * 曲谱页：只负责「找谱」。
     *
     * 原来这一页用「曲库 / 练习」两个标签把「挑曲谱」和「练曲谱」塞在同一页里 ——
     * 浏览与练习是两件事，却共用一个底部入口，切换还要多点一步标签。
     * 现在练习部分独立成第 5 个底部入口（见 buildSongPracticePage），本页专注曲库。
     */
    private View buildSongPage() {
        LinearLayout body = column();
        body.setPadding(dp(20), dp(4), dp(20), dp(24));

        songLibraryPanel=column();

        // 曲库清单每次构建页面时重新加载（rebuildUi 会清空 librarySongs，必须在这里补回）。
        librarySongs.clear();
        try { librarySongs.addAll(SongLibrary.load(this)); }
        catch (Exception error) { libraryLoadError = error.getMessage(); }

        // ---- 当前曲目 ----
        LinearLayout chosen = card();
        LinearLayout chosenHead = row();
        selectedSongLabel = kit.textBold("请选择曲目", ts(18), cTextPrimary());
        chosenHead.addView(selectedSongLabel, new LinearLayout.LayoutParams(0, -2, 1));
        chosen.addView(chosenHead);
        songMetaLabel = kit.text("选择曲谱，开始练习", ts(12), cTextSecondary());
        songMetaLabel.setPadding(0, dp(6), 0, 0);
        chosen.addView(songMetaLabel);
        TextView upload = primaryAction("同步曲谱到琴");
        upload.setOnClickListener(v -> { tap(); uploadSelectedSong(); });
        LinearLayout.LayoutParams uploadLp = new LinearLayout.LayoutParams(-1, -2);
        uploadLp.topMargin = dp(14);
        chosen.addView(upload, uploadLp);
        songStatusLabel = kit.text("尚未上传曲谱", ts(12), cTextSecondary());
        songStatusLabel.setPadding(0, dp(10), 0, 0);
        chosen.addView(songStatusLabel);
        TextView practiceNow=outlineAction("练习这首曲子");practiceNow.setOnClickListener(v->{showSongSection(true);requestLocalPractice();});LinearLayout.LayoutParams practiceLp=new LinearLayout.LayoutParams(-1,-2);practiceLp.topMargin=dp(12);chosen.addView(practiceNow,practiceLp);
        LinearLayout.LayoutParams chosenAuditionLp = new LinearLayout.LayoutParams(-1, -2);
        chosenAuditionLp.topMargin = dp(12);
        chosen.addView(buildAuditionControls(), chosenAuditionLp);
        addContent(body, chosen);

        // ---- 我的曲库（入口）----
        // 导入的曲谱不再堆在本页（导入越多页面越长，还会把练习曲库一起往下推），
        // 改为进入独立的「我的曲库」页面集中管理；这里只显示入口、回收站捷径与最近几首。
        songLibraryPanel.addView(sectionTitle("我的曲库"));
        LinearLayout myActions=row();
        TextView openLibrary=primaryAction("打开我的曲库");
        TextView importFile=outlineAction("导入曲谱");
        TextView recycle=outlineAction("回收站");
        fitSingleLine(openLibrary, 12, 8);
        fitSingleLine(importFile, 12, 8);
        fitSingleLine(recycle, 12, 8);
        myActions.addView(openLibrary,new LinearLayout.LayoutParams(0,-2,1.4f));addSpace(myActions,10);
        myActions.addView(importFile,new LinearLayout.LayoutParams(0,-2,1));addSpace(myActions,10);
        myActions.addView(recycle,new LinearLayout.LayoutParams(0,-2,.85f));
        openLibrary.setOnClickListener(v->openScoreLibrary());
        importFile.setOnClickListener(v->pickSong());
        recycle.setOnClickListener(v->showRecycleBin());
        rowMinHeight(myActions,46);
        addContent(songLibraryPanel,myActions);
        savedScores=column();addContent(songLibraryPanel,savedScores);rebuildSavedScores();

        // ---- 练习曲库 ----
        libraryCountLabel = sectionTitle("练习曲库");
        songLibraryPanel.addView(libraryCountLabel);
        EditText search = new EditText(this);
        search.setHint("曲名 / 拼音 / 首字母 / 作曲家");
        search.setText(libraryQuery);
        search.setHintTextColor(cTextTertiary());
        search.setTextColor(cTextPrimary());
        search.setTextSize(ts(14));
        search.setSingleLine(true);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(kit.bg(cSurface2(), 14));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable e) { libraryQuery = e.toString(); main.removeCallbacks(librarySearchRefresh); main.postDelayed(librarySearchRefresh,180); }
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(46));
        searchLp.bottomMargin = dp(10);
        songLibraryPanel.addView(search, searchLp);
        LinearLayout filters = row();
        String[] filterNames = {"全部", "入门", "进阶", "挑战", "按BPM"};
        // 用外间距代替 Space 占位：容器里全部是芯片，索引即芯片序号，选中态不会错位。
        final TextView[] filterChips = new TextView[filterNames.length];
        for (int i = 0; i < filterNames.length; ++i) {
            final int idx = i;
            TextView f = chip(filterNames[i], i == libraryFilter);
            fitSingleLine(f, 11, 6);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) fp.leftMargin = dp(6);
            filters.addView(f, fp);
            filterChips[i] = f;
            f.setOnClickListener(v -> {
                tap();
                libraryFilter = idx; libraryPage=0;
                for (int k = 0; k < filterChips.length; ++k) setChip(filterChips[k], k == libraryFilter);
                rebuildLibraryList();
            });
        }
        rowMinHeight(filters, 38); addContent(songLibraryPanel, filters);

        LinearLayout categories=row();String[] categoryNames={"所有风格","熟悉旋律","世界民谣","古典声乐"};
        TextView[] categoryChips=new TextView[categoryNames.length];
        for(int i=0;i<categoryNames.length;i++){final int index=i;TextView item=chip(categoryNames[i],i==libraryCategory);fitSingleLine(item,11,4);categoryChips[i]=item;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.leftMargin=dp(6);categories.addView(item,lp);item.setOnClickListener(v->{libraryCategory=index;libraryPage=0;for(int j=0;j<categoryChips.length;j++)setChip(categoryChips[j],j==index);rebuildLibraryList();});}
        rowMinHeight(categories,38);addContent(songLibraryPanel,categories);
        // 随机曲目：像抽卡一样从曲库里挑一首，降低“不知道练什么”的选择成本。
        TextView surprise = outlineAction("🎲 随机挑一首练习");
        LinearLayout.LayoutParams surpriseLp = new LinearLayout.LayoutParams(-1, -2);
        surpriseLp.bottomMargin = dp(10);
        surprise.setOnClickListener(v -> pickRandomSong());
        songLibraryPanel.addView(surprise, surpriseLp);

        libraryContainer = column();
        LinearLayout pager = row();
        pager.setGravity(Gravity.CENTER_VERTICAL);
        libraryPrevious = outlineAction("‹ 上一页");
        libraryNext = outlineAction("下一页 ›");
        fitSingleLine(libraryPrevious, 13, 8);
        fitSingleLine(libraryNext, 13, 8);
        libraryPageLabel = kit.textBold("", ts(13), cTextSecondary());
        libraryPageLabel.setGravity(Gravity.CENTER);
        pager.addView(libraryPrevious, new LinearLayout.LayoutParams(0, -2, 1));
        pager.addView(libraryPageLabel, new LinearLayout.LayoutParams(0, -2, 1));
        pager.addView(libraryNext, new LinearLayout.LayoutParams(0, -2, 1));
        rowMinHeight(pager, 44);
        libraryContainer.addView(pager);
        libraryPrevious.setOnClickListener(v -> changeLibraryPage(-1));
        libraryNext.setOnClickListener(v -> changeLibraryPage(1));
        practicePageList = new ScorePageList(this, this::changeLibraryPage);
        practicePageList.setPadding(0, dp(8), 0, 0);
        libraryContainer.addView(practicePageList);
        addContent(songLibraryPanel, libraryContainer);
        rebuildLibraryList();

        // ---- AI 曲谱助手（v3.12.0：搜索/网页/文本/相册识谱 → DeepSeek 转为可练习事件）----
        songLibraryPanel.addView(sectionTitle("AI 曲谱助手"));
        LinearLayout aiImportCard = card();
        aiImportCard.addView(cardLabel("让 DeepSeek 帮你找谱、转谱"));
        aiImportCard.addView(hint("支持搜索曲名、粘贴网页链接、粘贴简谱/歌词，或从相册选谱面照片；AI 生成弦位事件后仍建议先试听校对。"));
        TextView aiImportButton = primaryAction("打开 AI 曲谱助手");
        aiImportButton.setOnClickListener(v -> { tap(); openAiScoreImport(); });
        LinearLayout.LayoutParams aiImportLp = new LinearLayout.LayoutParams(-1, -2);
        aiImportLp.topMargin = dp(12);
        aiImportCard.addView(aiImportButton, aiImportLp);
        addContent(songLibraryPanel, aiImportCard);

        // ---- 导入更多曲谱 ----
        songLibraryPanel.addView(sectionTitle("导入更多曲谱"));
        LinearLayout cloud = card();

        urlText = new EditText(this);
        urlText.setHint("粘贴曲谱下载链接");
        urlText.setHintTextColor(cTextTertiary());
        urlText.setTextColor(cTextPrimary());
        urlText.setTextSize(ts(14));
        urlText.setSingleLine(true);
        urlText.setPadding(dp(14), 0, dp(14), 0);
        urlText.setBackground(kit.bg(cSurface2(), 14));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(-1, dp(46));
        urlLp.topMargin = dp(14);
        cloud.addView(urlText, urlLp);
        LinearLayout urlButtons = row();
        TextView download = tinta("在线下载");
        TextView open = outlineAction("选择本地文件");
        urlButtons.addView(download, new LinearLayout.LayoutParams(0, -2, 1));
        addSpace(urlButtons, 10);
        urlButtons.addView(open, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams ub = new LinearLayout.LayoutParams(-1, -2);
        ub.topMargin = dp(10);
        rowMinHeight(urlButtons, 46); cloud.addView(urlButtons, ub);
        download.setOnClickListener(v -> { tap(); downloadSong(); });
        open.setOnClickListener(v -> { tap(); pickSong(); });
        LinearLayout demoRow = row();
        rowMinHeight(demoRow, 44);
        TextView demo = outlineAction("内置 TXT");
        TextView demoMidi = outlineAction("内置 MIDI");
        demoRow.addView(demo, new LinearLayout.LayoutParams(0, -2, 1));
        addSpace(demoRow, 10);
        demoRow.addView(demoMidi, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams demoLp = new LinearLayout.LayoutParams(-1, -2);
        demoLp.topMargin = dp(10);

        demo.setOnClickListener(v -> { tap(); uploadBuiltinSong(); });
        demoMidi.setOnClickListener(v -> { tap(); uploadBuiltinMidi(); });
        addContent(songLibraryPanel, cloud);

        addContent(body,songLibraryPanel);

        return page(body);
    }

    /**
     * Practice page: score challenge, the scrolling guide and the on-device score guide.
     *
     * Split out of the song page, which used to hold both "find a score" and "practise it"
     * behind one bottom-nav entry with an extra tab switch in between.
     */
    private ScorePreviewPlayer auditionPlayer;
    /** AI 曲谱助手「多来源候选」的短试听播放器；与练习页试听互不干扰。 */
    private ScorePreviewPlayer aiCandidatePreview;
    private Runnable aiCandidatePreviewStop;
    private final AiPreviewSession aiPreviewSession=new AiPreviewSession();
    private AiScoreImportDialog.PreviewObserver aiPreviewObserver;
    private long aiPlaybackToken;
    private boolean aiPreviewStarting;

    private boolean auditionGuideActive,auditionPracticeMode;
    private String auditionMessage="";
    private final java.util.List<TextView> auditionLabels=new ArrayList<>();
    /** 试听音量控件的镜像：曲谱页和练习页各有一套，设置变化时都要同步。 */
    private final java.util.List<SeekBar> auditionVolumeBars=new ArrayList<>();
    private final java.util.List<TextView> auditionVolumeLabels=new ArrayList<>();
    private boolean updatingAuditionVolume;

    private LinearLayout buildAuditionControls(){
        LinearLayout box=column(),buttons=row();TextView play=outlineAction("▶ 试听乐谱"),stop=outlineAction("停止试听");
        buttons.addView(play,new LinearLayout.LayoutParams(0,-2,1));addSpace(buttons,10);buttons.addView(stop,new LinearLayout.LayoutParams(0,-2,1));box.addView(buttons);
        TextView state=kit.text(auditionMessage,ts(12),cTextSecondary());state.setPadding(0,dp(8),0,0);auditionLabels.add(state);box.addView(state);
        android.widget.Switch fadeToggle=new android.widget.Switch(this);fadeToggle.setText("指引灯光渐变");fadeToggle.setTextColor(cTextPrimary());
        fadeToggles.add(fadeToggle);fadeToggle.setChecked(guideSoft);fadeToggle.setEnabled(guideSoftKnown&&bleReady&&powerAllowsControls());
        fadeToggle.setOnCheckedChangeListener((b,on)->{if(!updatingFade){
            for(android.widget.Switch toggle:fadeToggles)toggle.setEnabled(false);
            if(writeCommand("GUIDE_FADE:"+(on?1:0)))main.postDelayed(()->{if(bleReady)writeCommand("GUIDE_FADE_GET");},700);
        }});box.addView(fadeToggle);
        if(bleReady&&!guideSoftKnown)writeCommand("GUIDE_FADE_GET");
        // 应用内试听音量：0–100% 衰减输出，100–200% 在合成器软限幅前继续增益；
        // 手机媒体音量拉满仍偏轻时可以用 >100% 档位继续提升（峰值仍由软限幅兜住）。
        LinearLayout volumeHead=row();volumeHead.setPadding(0,dp(10),0,0);
        volumeHead.addView(kit.text("试听音量",ts(12),cTextSecondary()),new LinearLayout.LayoutParams(0,-2,1));
        int volume=settings==null?130:settings.previewVolume();
        TextView volumeValue=kit.text(volume+"%",ts(12),cTextSecondary());
        volumeHead.addView(volumeValue);auditionVolumeLabels.add(volumeValue);
        box.addView(volumeHead);
        SeekBar volumeBar=kit.seekBar(cAccent());volumeBar.setMax(200);volumeBar.setProgress(volume);
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean fromUser){if(fromUser&&!updatingAuditionVolume&&settings!=null)settings.setPreviewVolume(p);}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        auditionVolumeBars.add(volumeBar);box.addView(volumeBar);
        play.setOnClickListener(v->{tap();startScoreAudition();});stop.setOnClickListener(v->{tap();if(auditionPlayer!=null)auditionPlayer.stop();});return box;
    }
    private void stopScoreAudition(){if(auditionPlayer!=null)auditionPlayer.stop();}

    /** 把设置里的试听音量同步到播放器与所有滑杆（曲谱页与练习页各一套，可能同时存在）。 */
    private void applyAuditionVolume(){
        if(settings==null)return;
        int value=settings.previewVolume();
        if(auditionPlayer!=null)auditionPlayer.setVolume(value/100f);
        updatingAuditionVolume=true;
        try{
            for(SeekBar bar:auditionVolumeBars)if(bar!=null&&bar.getProgress()!=value)bar.setProgress(value);
            for(TextView label:auditionVolumeLabels)if(label!=null)label.setText(value+"%");
        }finally{updatingAuditionVolume=false;}
    }

    private void startScoreAuditionAfterChoice(boolean jump){
        auditionJumpToPractice=jump;
        playScoreAudition(jump);
    }
    private void startScoreAudition(){
        if(selectedTimeline==null&&selectedSavedId==null){toast("请先选择曲谱；PDF需先完成识谱");return;}
        if(fullCountdown.active()||hasDeviceSongIntent()||(deviceSongState!=null&&deviceSongState.state!=0)){toast("请先停止设备指引或取消倒数，再试听");return;}
        stopScoreAudition();
        final String song=selectedSongId;
        AppDialogs.message(this,"试听前选择","是否进入滚动跟弹，同步查看音符？",
            "是，进入跟弹",(d,w)->confirmAuditionChoice(song,true),
            "否，留在本页",(d,w)->confirmAuditionChoice(song,false),"取消",null);
    }
    private void confirmAuditionChoice(String song,boolean jump){
        if(!activityVisible||!java.util.Objects.equals(song,selectedSongId)){toast("曲目已改变，请重新试听");return;}
        startScoreAuditionAfterChoice(jump);
    }
    private void playScoreAudition(boolean jump){
        if(selectedTimeline==null&&selectedSavedId==null){toast("请先选择曲谱；PDF需先完成识谱");return;}
        if(fullCountdown.active()||hasDeviceSongIntent()||(deviceSongState!=null&&deviceSongState.state!=0)){toast("请先停止设备指引或取消倒数，再试听");return;}
        auditionPracticeMode=true;
        stopSongGuide("手机试听中");
        if(jump)showSongSection(true);
        if(jump&&fullScreenDialog==null&&pages[3] instanceof ScrollView)pages[3].post(()->{
            if(fullScreenDialog!=null)return;
            android.graphics.Rect rect=new android.graphics.Rect();songGuideView.getDrawingRect(rect);
            ScrollView scroll=(ScrollView)pages[3];scroll.offsetDescendantRectToMyCoords(songGuideView,rect);scroll.smoothScrollTo(0,Math.max(0,rect.top-dp(90)));
        });
        if(auditionPlayer==null)auditionPlayer=new ScorePreviewPlayer(this,new ScorePreviewPlayer.Listener(){
            public void state(String value){auditionMessage=value;for(TextView label:auditionLabels)label.setText(value);}
            public void ready(long duration){
                auditionGuideActive=true;abortScoring("手机试听不计入评分");guideDeviceReference=false;guideRunning=false;
                main.removeCallbacks(guideTick);songGuideView.clearDeviceEvent();songGuideView.setAudition(true);songGuideView.setElapsed(0);
                songGuideModeLabel.setText("乐谱试听 · 画面随声音同步");
            }
            public void progress(long position,long duration,boolean paused,double rate){
                if(!auditionGuideActive)return;
                renderGuideProgress(position,duration,rate);
                songGuideModeLabel.setText(paused?"试听已暂停":"乐谱试听 · "+rate+"×");
            }
            public void ended(){auditionGuideActive=false;songGuideView.setAudition(false);guideRunning=false;main.removeCallbacks(guideTick);songGuideModeLabel.setText(auditionMessage);}
        });
        final String id=selectedSavedId,name=selectedSongName;final SongTimeline timeline=selectedTimeline;
        // 开播前先套用当前设置音量；播放中拖动滑杆会走 onSettingChanged → applyAuditionVolume。
        if(settings!=null)auditionPlayer.setVolume(settings.previewVolume()/100f);
        auditionPlayer.setTranspose(activeCapo());auditionPlayer.start(()->{
            if(id!=null){ScoreRepository.Item item=scoreRepo.get(id);if(item.deleted!=0)throw new Exception("曲谱已在回收站");byte[] original=ScoreRepository.readAtomic(scoreRepo.source(id));
                if(PreviewScore.isMidi(original))return PreviewScore.fromMidi(original).shifted(item.midiTranspose);
                try{return PreviewScore.fromTimeline(SongTimeline.parse(scoreRepo.events(id)));}catch(Exception e){throw new Exception("请先完成识谱并生成指引，再试听");}
            }
            if(timeline==null)throw new Exception("没有可试听音符");return PreviewScore.fromTimeline(timeline);
        },guideSpeed,name==null?"当前曲谱":name);
    }

    /** 本次练习与设备操作置顶；试听选择去向；跟弹内集中速度、评分与显示设置。 */
    private View buildSongPracticePage() {
        practiceRateBars.clear();practiceRateLabels.clear();
        LinearLayout page=column();page.setPadding(dp(20),dp(4),dp(20),dp(24));
        page.addView(sectionTitle("本次练习"));
        LinearLayout summary=card(),head=row();
        practiceSongLabel=kit.textBold("尚未选择曲目",ts(16),cTextPrimary());
        practiceSongLabel.setMaxLines(2);practiceSongLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(practiceSongLabel,new LinearLayout.LayoutParams(0,-2,1));
        TextView change=outlineAction("换曲");fitSingleLine(change,12,10);change.setOnClickListener(v->{tap();showPage(2);});head.addView(change);summary.addView(head);
        practiceSongStatus=badge("未选曲",cSurface3(),cTextSecondary());
        LinearLayout.LayoutParams statusLp=new LinearLayout.LayoutParams(-2,-2);statusLp.topMargin=dp(8);summary.addView(practiceSongStatus,statusLp);
        practiceSongMetaLabel=hint("");practiceSongMetaLabel.setPadding(0,dp(8),0,0);summary.addView(practiceSongMetaLabel);
        practiceCountdownLabel=kit.textBold("",ts(20),cAccent());practiceCountdownLabel.setVisibility(View.GONE);summary.addView(practiceCountdownLabel);
        TextView start=primaryAction("开始练习"),stop=outlineAction("停止练习");
        start.setOnClickListener(v->{tap();requestLocalPractice();});stop.setOnClickListener(v->{tap();stopPractice();});
        addPracticeButtonRow(summary,12,start,stop);
        songPlayButton=primaryAction("开始设备指引");TextView stopDevice=outlineAction("停止设备");
        songPlayButton.setOnClickListener(v->{tap();startGuidedPractice();});
        stopDevice.setOnClickListener(v->{tap();fullGuideAfterUploadId=null;guideUploadGeneration++;cancelFullCountdown();stopScoreAudition();if(bleReady)stopDeviceSong();else stopSongGuide("设备未连接 · 本地已停止");});
        addPracticeButtonRow(summary,8,songPlayButton,stopDevice);addContent(page,summary);

        page.addView(sectionTitle("试听参考"));LinearLayout audition=card();audition.addView(buildAuditionControls());addContent(page,audition);

        page.addView(sectionTitle("跟弹练习"));LinearLayout guide=card(),guideHead=row();
        songGuideModeLabel=kit.text("准备开始",ts(12),cAccent());songGuideModeLabel.setSingleLine(true);songGuideModeLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        guideHead.addView(songGuideModeLabel,new LinearLayout.LayoutParams(0,-2,1));
        TextView full=tinta("全屏跟弹");fitSingleLine(full,12,10);full.setOnClickListener(v->{tap();openFullScreen(songGuideView,true);});guideHead.addView(full);guide.addView(guideHead);
        songProgressLabel=kit.text("00:00 / 00:00",ts(12),cTextSecondary());songProgressLabel.setPadding(0,dp(8),0,0);guide.addView(songProgressLabel);
        songGuideView=new SongGuideView(this);songGuideView.setCapo(boardCapo());songGuideView.setLandingRight(settings.guideLandingRight());songGuideView.setStringsReversed(settings.guideStringsReversed());
        LinearLayout.LayoutParams viewLp=new LinearLayout.LayoutParams(-1,dp(268));viewLp.topMargin=dp(10);guide.addView(songGuideView,viewLp);
        guideSceneButton=outlineAction(guideSceneLabel());
        guideSceneButton.setOnClickListener(v->chooseGuideScene());
        songGuideView.setScene(guideScene());
        addPracticeButtonRow(guide,8,guideSceneButton);
        guide.addView(buildPracticeSpeedControls());
        TextView instrument=primaryAction("乐器自动播放"),instrumentStop=outlineAction("停止播放");
        instrument.setOnClickListener(v->{tap();startInstrumentPlayback();});instrumentStop.setOnClickListener(v->{tap();stopPractice();instrumentIntent=false;});
        addPracticeButtonRow(guide,8,instrument,instrumentStop);
        TextView local=primaryAction("无指引练习"),device=outlineAction("设备指引");
        local.setOnClickListener(v->{tap();requestLocalPractice();});device.setOnClickListener(v->{tap();startGuidedPractice();});addPracticeButtonRow(guide,8,local,device);
        guideDirectionButton=outlineAction(guideDirectionText(true));guideStringOrderButton=outlineAction(stringOrderText());
        guideDirectionButton.setOnClickListener(v->toggleGuideDirection());guideStringOrderButton.setOnClickListener(v->{tap();toggleGuideStrings();});
        addPracticeButtonRow(guide,8,guideDirectionButton,guideStringOrderButton);
        LinearLayout.LayoutParams scoringLp=new LinearLayout.LayoutParams(-1,-2);scoringLp.topMargin=dp(16);guide.addView(buildScoringCard(),scoringLp);
        addContent(page,guide);refreshPracticeSummary();return page(page);
    }
    /** Equal-width controls share an explicit row gap; large fonts never push adjacent rows together. */
    private void addPracticeButtonRow(LinearLayout parent,int top,TextView... buttons){
        LinearLayout line=row();
        for(int i=0;i<buttons.length;i++){
            if(i>0)addSpace(line,8);fitSingleLine(buttons[i],14,6);
            line.addView(buttons[i],new LinearLayout.LayoutParams(0,-2,1));
        }
        rowMinHeight(line,46);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(top);parent.addView(line,lp);
    }
    private GuideScene guideScene(){
        android.content.SharedPreferences prefs=getSharedPreferences("creative_studio",0);
        return GuideScene.byId(prefs.getString("guide_scene",prefs.getBoolean("lanterns",false)?"stars":"classic"));
    }
    private String guideSceneLabel(){return guideScene().label+" ▾";}
    private void chooseGuideScene(){
        tap();
        GuideScene[] scenes=GuideScene.values();
        java.util.List<AppDialogs.Item> items=new ArrayList<>();
        for(GuideScene scene:scenes){
            items.add(new AppDialogs.Item(scene.label,scene.detail,false,scene==guideScene()?"当前":null));
        }
        AppDialogs.list(this,"跟弹场景",items,i->setGuideScene(scenes[i]),"返回",null,null,null);
    }
    private void setGuideScene(GuideScene scene){
        getSharedPreferences("creative_studio",0).edit().putString("guide_scene",scene.id).apply();
        if(songGuideView!=null)songGuideView.setScene(scene);
        if(guideSceneButton!=null)guideSceneButton.setText(guideSceneLabel());
        if(fullGuideSceneButton!=null)fullGuideSceneButton.setText(guideSceneLabel());
    }
    private String stringOrderText(){return settings.guideStringsReversed()?"弦序 4→1":"弦序 1→4";}
    private void toggleGuideStrings(){settings.setGuideStringsReversed(!settings.guideStringsReversed());updateGuideDirection();}
    private void requestLocalPractice(){
        if(deviceSongState!=null&&deviceSongState.state!=0&&deviceSongState.instrument){toast("请先停止乐器自动播放，再开始人工练习");return;}
        instrumentIntent=false;
        if(selectedTimeline==null){toast("请先选择可练习曲谱");return;}
        if(scoringEnabled&&!canScoreNow()){toast("请连接乐器等待音符回读，或先关闭评分");return;}
        if(scoringEnabled&&!capoRangePlayable())return;
        instrumentIntent=false;fullGuideAfterUploadId=null;guideUploadGeneration++;auditionPracticeMode=false;beginFullCountdown(false);
    }
    private void stopPractice(){
        careerStage=-1;careerScorer=null;
        fullGuideAfterUploadId=null;guideUploadGeneration++;cancelFullCountdown();auditionPracticeMode=false;stopScoreAudition();
        if(hasDeviceSongIntent()||(deviceSongState!=null&&deviceSongState.state!=0))stopDeviceSong();
        else stopSongGuide("练习已停止");
        instrumentIntent=false;scorePaused=false;guideElapsedBase=0;updateScoringUi();
    }
    private LinearLayout buildPracticeSpeedControls(){
        LinearLayout box=column(),head=row();box.setPadding(0,dp(8),0,0);
        head.addView(kit.text("练习倍速",ts(12),cTextSecondary()),new LinearLayout.LayoutParams(0,-2,1));
        TextView value=kit.textBold("",ts(12),cAccent());head.addView(value);box.addView(head);
        SeekBar bar=kit.seekBar(cAccent());bar.setMax(7);bar.setContentDescription("练习倍速，0.25至2倍，步长0.25");
        practiceRateBars.add(bar);practiceRateLabels.add(value);box.addView(bar,new LinearLayout.LayoutParams(-1,dp(36)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){if(user&&!updatingPracticeRate)value.setText(rateText((p+1)*.25)+" · 松开应用");}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){setPracticeSpeed((b.getProgress()+1)*.25);}
        });updatePracticeSpeedControls();return box;
    }
    private String rateText(double rate){return String.format(java.util.Locale.ROOT,"%.2f×",rate);}
    private void updatePracticeSpeedControls(){
        updatingPracticeRate=true;
        try{for(SeekBar b:practiceRateBars){b.setProgress((int)Math.round(guideSpeed*4)-1);b.setEnabled(pendingSongControlToken==0&&!deviceStopRequested);}
            for(TextView t:practiceRateLabels)t.setText(rateText(guideSpeed)+(pendingSongControlToken!=0&&pendingSongControlOp==1?" · 等待设备确认":""));
        }finally{updatingPracticeRate=false;}
    }
    private void setPracticeSpeed(double rate){
        if(!SongPlaybackState.validRate((int)Math.round(rate*1000))){updatePracticeSpeedControls();return;}
        if(deviceStopRequested||pendingSongControlToken!=0){toast("等待设备确认上一操作");updatePracticeSpeedControls();return;}
        if(guideSpeed==rate){updatePracticeSpeedControls();return;}
        cancelFullCountdown();
        if(guideDeviceReference&&hardwarePlayingId!=null){
            if(!songTempoSupported)toast("设备实时变速需要配套新版固件");
            else if(!requestSongControl(1,(int)Math.round(rate*1000)))toast("倍速指令未发送");
        }else{
            applyGuideRate(rate);if(auditionPlayer!=null&&auditionPlayer.isActive())auditionPlayer.setSpeed(rate);
            if(songGuideModeLabel!=null)songGuideModeLabel.setText("练习速度 "+rateText(guideSpeed));
        }
        updatePracticeSpeedControls();
    }

    /** 练习页顶部摘要：曲名、时长/音符数、是否已同步，以及未选曲时的引导。 */
    private void refreshPracticeSummary() {
        if (practiceSongLabel == null) return;
        boolean hasSong = selectedSongName != null && !selectedSongName.isEmpty() && selectedTimeline != null;
        if (!hasSong) {
            practiceSongLabel.setText("尚未选择曲目");
            practiceSongLabel.setTextColor(cTextSecondary());
            practiceSongMetaLabel.setText("从曲库选曲后即可开始练习。");
            practiceSongStatus.setText("未选曲");
            practiceSongStatus.setBackground(kit.bg(cSurface3(), 999));
            practiceSongStatus.setTextColor(cTextSecondary());
            return;
        }
        practiceSongLabel.setText(selectedSongName);
        practiceSongLabel.setTextColor(cTextPrimary());
        boolean uploaded = selectedSongId != null && selectedSongId.equals(uploadedSongId);
        StringBuilder meta = new StringBuilder();
        meta.append(formatSongTime(selectedTimeline.durationMs))
            .append(" · ").append(selectedTimeline.noteCount).append(" 个弦位音符")
            .append(uploaded ? " · 已同步到设备" : " · 尚未同步到设备");
        practiceSongMetaLabel.setText(meta.toString());
        practiceSongStatus.setText(uploaded ? "已同步" : "未同步");
        practiceSongStatus.setBackground(kit.bg(uploaded ? cAccentSoft() : cSurface3(), 999));
        practiceSongStatus.setTextColor(uploaded ? cAccent() : cTextSecondary());
    }

    private void changeLibraryPage(int delta) {
        if (delta < 0 ? !libraryPrevious.isEnabled() : !libraryNext.isEnabled()) return;
        tap();
        libraryPage += delta;
        rebuildLibraryList();
    }

    /** Filters apply to the whole catalog, then only the selected page is rendered. */
    private void rebuildLibraryList() {
        if (practicePageList == null) return;
        practicePageList.removeAllViews();
        libraryRows.clear();
        ArrayList<SongLibrary.Song> visible = new ArrayList<>();
        for (SongLibrary.Song song : SongLibrary.search(librarySongs, libraryQuery))
            if (matchesLibraryFilter(song)) visible.add(song);
        if (libraryFilter == 4) visible.sort((a, b) -> Integer.compare(a.bpm, b.bpm));
        int pages = (visible.size() + LIBRARY_PAGE_SIZE - 1) / LIBRARY_PAGE_SIZE;
        libraryPage = Math.max(0, Math.min(libraryPage, pages - 1));
        ((View) libraryPageLabel.getParent()).setVisibility(pages == 0 ? View.GONE : View.VISIBLE);
        libraryPageLabel.setText((libraryPage + 1) + " / " + pages);
        libraryPageLabel.setContentDescription("第 " + (libraryPage + 1) + " 页，共 " + pages + " 页");
        setLibraryPageButton(libraryPrevious, libraryPage > 0);
        setLibraryPageButton(libraryNext, libraryPage + 1 < pages);

        if (librarySongs.isEmpty()) {
            libraryCountLabel.setText("练习曲库");
            String reason = libraryLoadError == null ? "曲库清单为空" : "读取失败：" + libraryLoadError;
            practicePageList.addView(kit.text("无法加载曲库（" + reason + "），请重新安装完整安装包。", ts(13), cDanger()));
            return;
        }
        libraryCountLabel.setText("练习曲库 · " + visible.size() + " / " + librarySongs.size() + " 首");
        if (visible.isEmpty()) {
            practicePageList.addView(kit.text("没有符合条件的曲目，试试清空搜索或选择“全部”。", ts(13), cTextSecondary()));
            return;
        }
        int from = libraryPage * LIBRARY_PAGE_SIZE;
        for (int i = from; i < Math.min(from + LIBRARY_PAGE_SIZE, visible.size()); i++)
            practicePageList.addView(libraryRow(visible.get(i)));
    }

    private void setLibraryPageButton(TextView button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .38f);
    }

    /**
     * 随机挑一首曲目。优先在当前筛选/搜索结果里挑；若结果为空则从整个曲库挑。
     * 只调用既有的 selectLibrarySong 流程，选曲、恢复、上传语义完全不变。
     */
    private void pickRandomSong() {
        if (librarySongs.isEmpty()) { toast("曲库为空，无法随机推荐"); return; }
        ArrayList<SongLibrary.Song> pool = new ArrayList<>();
        for(SongLibrary.Song song:SongLibrary.search(librarySongs,libraryQuery))if(matchesLibraryFilter(song))pool.add(song);
        if (pool.isEmpty()) pool.addAll(librarySongs);
        SongLibrary.Song picked = pool.get(funRandom.nextInt(pool.size()));
        tap();
        selectLibrarySong(picked);
        toast("随机推荐：" + picked.title + " · " + picked.difficulty + " · " + picked.bpm + " BPM");
    }

    private boolean matchesLibraryFilter(SongLibrary.Song song) {
        if(libraryCategory>0&&!song.styles.contains(new String[]{"","熟悉旋律","世界民谣","古典声乐"}[libraryCategory]))return false;
        if (libraryFilter >= 1 && libraryFilter <= 3) {
            String want = libraryFilter == 1 ? "入门" : libraryFilter == 2 ? "进阶" : "挑战";
            return song.difficulty != null && song.difficulty.contains(want);
        }
        return true;
    }

    /** 单条曲目行：主标题 + 元信息 + 右侧状态。选中态用 accent 描边与文字区分。 */
    private LinearLayout libraryRow(SongLibrary.Song song) {
        LinearLayout item = row();
        item.setPadding(dp(14), dp(12), dp(14), dp(12));
        item.setBackground(kit.bg(cSurface(), 14, cStroke(), Math.max(1, dp(1))));
        item.setClickable(true); item.setFocusable(true);
        LinearLayout texts = column();
        TextView title = kit.textBold(song.title, ts(14), cTextPrimary());
        TextView meta = kit.text(song.subtitle + " · " + song.bpm + " BPM · " + song.difficulty, ts(12), cTextSecondary());
        meta.setPadding(0, dp(3), 0, 0);
        texts.addView(title);
        texts.addView(meta);
        item.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        TextView tag = kit.text(formatSongTime(song.durationMs), ts(11), cTextTertiary());
        tag.setPadding(dp(8), dp(3), dp(8), dp(3));
        tag.setBackground(kit.bg(cSurface2(), 999));
        item.addView(tag);
        item.setOnClickListener(v -> { tap(); selectLibrarySong(song); });
        item.setOnLongClickListener(v->{new android.app.AlertDialog.Builder(this).setTitle(song.title).setMessage(song.subtitle+"\n"+song.arrangement+"\n\n"+song.license+"\n"+song.sourceUrl).setPositiveButton("知道了",null).show();return true;});
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        item.setLayoutParams(lp);
        libraryRows.add(new LibraryRow(song.id, item, title, meta));
        return item;
    }

    private String formatSongTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void selectLibrarySong(SongLibrary.Song song) {        try {
            byte[] normalized = SongLibrary.events(this, song.asset);
            if (normalized == null) throw new IllegalArgumentException("曲目事件格式错误");
            SongTimeline timeline = SongTimeline.parse(normalized);
            if (timeline.durationMs != song.durationMs || timeline.noteCount != song.noteCount)
                throw new IllegalArgumentException("曲目清单与实际事件不一致");
            selectSong("library:" + song.id, song.title, normalized, timeline,
                song.subtitle + " · " + song.bpm + " BPM · " + song.difficulty + "\n" + song.arrangement +
                "\n" + formatSongTime(timeline.durationMs) + " · " + timeline.noteCount + " 个弦位音符",
                !restoringSong && !programmaticSelection);
            rememberSelectedSong("library:" + song.id, song.title, SettingsStore.KIND_LIBRARY, null);
        } catch (Exception error) {
            if (!restoringSong) toast("选曲失败：" + error.getMessage());
        }
    }

    // ---- 记住上次曲目 ----
    /**
     * 记录当前曲目，供下次启动恢复。
     * 只有「用户主动选曲」才写入：程序化选中（进入曲谱页默认选第一首）与启动恢复本身
     * 都不写，否则历史记录会在启动瞬间被自己覆盖成曲库第一首。
     * 导入曲目的事件字节一并编码保存，这样导入的曲谱在下次启动也能恢复。
     */
    private void rememberSelectedSong(String id, String name, String kind, byte[] bytes) {
        if (programmaticSelection || restoringSong) return;
        if (settings == null || !settings.rememberLastSong()) return;
        settings.setLastSongId(id == null ? "" : id);
        settings.setLastSongName(name == null ? "" : name);
        settings.setLastSongKind(kind == null ? SettingsStore.KIND_NONE : kind);
        settings.setLastSongBytes(bytes == null ? 0 : bytes.length);
        settings.setLastSongAsset(bytes == null ? "" : encodeSongBytes(bytes));
    }

    /** 启动 / 界面重建后恢复上次曲目；失败静默跳过，不影响正常使用。 */
    private void restoreLastSong() {
        if (settings == null || !settings.rememberLastSong()) return;
        restoringSong = true;
        try {
            restoreLastSongInternal();
        } catch (Exception ignored) {
            // SharedPreferences 被外部篡改/损坏时也不能让启动崩溃：恢复失败即停在本页默认选中。
        } finally {
            restoringSong = false;
        }
    }

    private void restoreLastSongInternal() {
        String last=settings.lastSongId();
        if(last!=null && last.startsWith("saved:")){try{ScoreRepository.Item x=scoreRepo.get(last.substring(6));if(x.deleted==0)selectSavedScore(x.id);}catch(Exception ignored){}return;}
        String kind = settings.lastSongKind();
        if (SettingsStore.KIND_LIBRARY.equals(kind)) {
            String id = settings.lastSongId();
            if (id == null || !id.startsWith("library:")) return;
            String wanted = id.substring("library:".length());
            for (SongLibrary.Song song : librarySongs) {
                if (!wanted.equals(song.id)) continue;
                selectLibrarySong(song);
                return;
            }
        } else if (SettingsStore.KIND_IMPORT.equals(kind)) {
            byte[] stored = decodeSongBytes(settings.lastSongAsset(), settings.lastSongBytes());
            if (stored == null) return;
            SongTimeline timeline = SongTimeline.parse(stored);
            // 恢复上次曲目：不要停掉设备上正在播放的曲子。
            selectSong("import:" + (++importedSongSequence), settings.lastSongName(), stored, timeline,
                "已恢复上次导入的曲目 · " + formatSongTime(timeline.durationMs) + " · " + timeline.noteCount + " 个弦位音符\n先本地预览，再使用“上传所选曲目到 ESP32”。", false);
        }
    }

    private String encodeSongBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    private byte[] decodeSongBytes(String encoded, int expectedLength) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] decoded = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
            if (decoded == null || decoded.length == 0) return null;
            if (expectedLength > 0 && decoded.length != expectedLength) return null;
            return decoded;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * @param stopDevicePlay 选中曲目时是否要停掉设备正在播放的曲子。
     *        用户主动换曲 = true（沿用原有行为）；
     *        界面重建/启动恢复 = false —— 那只是把界面重新套用一套配色，
     *        不该把设备上正在弹的曲子停掉。
     */
    private void selectSong(String id, String name, byte[] song, SongTimeline timeline, String metadata, boolean stopDevicePlay) {
        careerStage=-1;careerScorer=null;
        ++savedScoreRequest; // an older asynchronous library read cannot replace this selection
        if(song[4]==1){song=GuideSong.upgrade(song);timeline=SongTimeline.parse(song);}
        if(id.equals(uploadedSongId)&&!java.util.Arrays.equals(song,selectedSongBytes))uploadedSongId=null;
        cancelFullCountdown();fullGuideAfterUploadId=null;guideUploadGeneration++;
        boolean contentChanged=id.equals(selectedSongId)&&!java.util.Arrays.equals(song,selectedSongBytes);
        if(contentChanged&&id.equals(pendingUploadId)){
            songQueue.clear();pendingUploadId=null;pendingUploadName=null;
            if(uploadAckTimeout!=null)main.removeCallbacks(uploadAckTimeout);
        }
        if(contentChanged)uploadDeviceConfirmed=false;
        if (!id.equals(selectedSongId)||contentChanged) {auditionPracticeMode=false;stopScoreAudition();}
        boolean hadDevicePlay = hasDeviceSongIntent();
        cancelPendingSongPlay();
        stopSongGuide("本地练习");
        if (stopDevicePlay && hadDevicePlay && gatt != null && bleReady) writeCommand("SONG_STOP");
        selectedSavedId=id.startsWith("saved:")?id.substring(6):null;
        selectedSongId = id; selectedSongName = name; selectedSongBytes = song; selectedTimeline = timeline;
        practiceScorer=null;scoreHint="";scoreGeneration++;loadBestScore();
        selectedSongLabel.setText(name); songMetaLabel.setText(metadata);
        songGuideView.setTimeline(timeline);
        songProgressLabel.setText("00:00 / " + formatSongTime(timeline.durationMs));
        for (int i = 0; i < libraryRows.size(); i++) {
            LibraryRow row = libraryRows.get(i);
            boolean selected = id.equals("library:" + row.songId);
            row.container.setBackground(selected
                ? kit.bg(cAccentSoft(), 14, cAccent(), Math.max(1, dp(1)))
                : kit.bg(cSurface(), 14, cStroke(), Math.max(1, dp(1))));
            row.title.setTextColor(selected ? cAccent() : cTextPrimary());
            row.meta.setTextColor(selected ? cAccent() : cTextSecondary());
        }
        if (pendingUploadId != null) songStatusLabel.setText("正在发送 " + pendingUploadName + "；当前选曲需另行上传");
        else songStatusLabel.setText(id.equals(uploadedSongId) ? "当前曲目数据已发送，等待设备确认" : "已选曲 · 尚未上传到设备");
        updateSongPlayState();
        if (pages[2] instanceof ScrollView) ((ScrollView) pages[2]).smoothScrollTo(0, 0);
        refreshPracticeSummary();
    }

    private void selectImportedSong(byte[] song,String name){
        try{ScoreRepository.Item item=scoreRepo.importFile(name,"application/x-uks",song);scoreRepo.setEvents(item,song,"音域完整",0);rebuildSavedScores();selectSavedScore(item.id);}catch(Exception e){toast("保存曲谱失败："+e.getMessage());}
    }

    private void uploadSelectedSong() {
        if (selectedSongBytes == null || selectedSongId == null) { toast("请先选曲或导入曲谱"); return; }
        if(selectedSavedId!=null)try{ScoreRepository.Item item=scoreRepo.get(selectedSavedId);if(item.outside>0){final String warn=item.warning;AppDialogs.message(this,"曲谱音域不完整",warn+"。仅同步可演奏音符，原谱完整保留。","同步可用部分",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){queueSong(selectedSongBytes,selectedSongName);}},"返回",null,null,null);return;}}catch(Exception e){toast("曲目不可用");return;}
        queueSong(selectedSongBytes, selectedSongName);
    }

    private void updateSongPlayState() {
        if (songPlayButton == null) return;
        boolean enabled = powerAllowsControls() && activityVisible && bleReady && selectedSongId != null &&
            selectedSongBytes!=null && pendingUploadId == null && songQueue.isEmpty() && queuedPlayId == null && pendingSongControlToken==0;
        songPlayButton.setEnabled(enabled); songPlayButton.setAlpha(enabled ? 1f : .45f);
        refreshPracticeSummary();updatePracticeSpeedControls();
    }

    private void startLocalPreview() {
        auditionPracticeMode=false;
        stopScoreAudition();
        if (selectedTimeline == null) { toast("请先选曲或导入事件曲谱"); return; }
        if(scoringEnabled){startScoredPractice();return;}
        if (hasDeviceSongIntent() || (deviceSongState!=null&&deviceSongState.state!=0)) stopDeviceSong();
        startSongGuide(false);
    }

    private void startSongGuide(boolean deviceReference) {
        abortScoring("练习重新开始");
        main.removeCallbacks(guideTick);
        if (selectedTimeline == null || !activityVisible) return;
        localGuidePaused = false;
        guideDeviceReference = deviceReference; guideRunning = true; main.removeCallbacks(practiceLease);if(!deviceReference && bleReady)main.post(practiceLease); guideElapsedBase=0;guideStartedAt = SystemClock.uptimeMillis();
        songGuideView.clearDeviceEvent();
        songGuideModeLabel.setText(deviceReference ? "本地进度参考 · 播放指令已发送，设备执行待确认" : "本地练习");
        // 设备确认进入播放后才解锁「登台演奏」，只代表首次成功走通设备播放链路。
        if (deviceReference) unlockBadge(ACH_FIRST_PLAY);
        main.post(guideTick);
    }

    private void stopSongGuide(String message) {
        if (!pausingPractice) {
            localGuidePaused = false;
            abortScoring(message);
        }
        main.removeCallbacks(guideTick); main.removeCallbacks(practiceLease);if(guideRunning && !guideDeviceReference && bleReady)writeCommand("PRACTICE:0");guideRunning = false;
        if (songGuideModeLabel != null) songGuideModeLabel.setText(message);
    }

    private boolean canPlaySelectedSong() {
        return activityVisible && selectedSongId != null && selectedSongId.equals(uploadedSongId) &&
            pendingUploadId == null && songQueue.isEmpty();
    }

    private void playSelectedSong() {
        if (!canPlaySelectedSong()) { toast("请先上传当前曲目并等待数据发送完成"); return; }
        if (queuedPlayId != null) { toast("播放指令正在发送"); return; }
        stopSongGuide("正在发送所选曲目的播放指令…");
        queuedPlayId = selectedSongId;
        queuedPlayToken = ++playRequestSequence;
        if(songTempoSupported||instrumentIntent){
            if(!requestSongControl(0,(int)Math.round(guideSpeed*1000))){cancelPendingSongPlay();songGuideModeLabel.setText("播放指令未发送");}
        }else if(guideSpeed!=1d){cancelPendingSongPlay();toast("设备倍速指引需要新版 ESP32 固件，请升级后重新连接");songGuideModeLabel.setText("当前设备尚未确认倍速支持");writeCommand("SONG_INFO");}
        else if (!writeCommand("SONG_PLAY")) { cancelPendingSongPlay(); songGuideModeLabel.setText("播放指令未发送"); }
        updateSongPlayState();
    }

    private void cancelPendingSongPlay() {
        commandQueue.cancelSongControls(songWriteBusy && activeWrite == commandChar);
        clearSongControlPending();
        queuedPlayId = null;
        queuedPlayToken = ++playRequestSequence;
    }

    private boolean hasDeviceSongIntent() {
        return hardwarePlayingId != null || queuedPlayId != null ||
            (songWriteBusy && activeWrite == commandChar && activePlayId != null);
    }

    private void stopDeviceSong() {
        cancelPendingSongPlay(); stopSongGuide("本地引导已停止");
        deviceStopRequested=true;
        if (writeCommand("SONG_STOP")) songGuideModeLabel.setText("停止指令已排队 · 设备停止状态待确认");
        else songGuideModeLabel.setText("本地引导已停止 · 停止指令未发送");
        updateSongPlayState();
    }

    // ================= 设置页 =================

    private void fitSingleLine(TextView view, float maxSp, int horizontalPaddingDp) {
        int minimumHeight = view.getMinHeight();
        view.setSingleLine(true);
        // setSingleLine resets TextView's pixel minimum to a line count; keep its touch target.
        if (minimumHeight > 0) view.setMinHeight(minimumHeight);
        view.setHorizontallyScrolling(false);
        view.setPadding(dp(horizontalPaddingDp), view.getPaddingTop(), dp(horizontalPaddingDp), view.getPaddingBottom());
        view.setAutoSizeTextTypeUniformWithConfiguration(8, Math.max(9, Math.round(ts(maxSp))), 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
    }

    private ChainState chainState;private boolean chainPending=false,chainExpired=true,chainUpdating=false;
    private int chainTicket=0,chainTarget=0,chainMask=0;
    private final boolean[] chainDragging=new boolean[4];
    private String chainFeedback;private final int[] chainAmounts={64,48,48,64};
    private final android.widget.CheckBox[] chainChecks=new android.widget.CheckBox[4];private final SeekBar[] chainBars=new SeekBar[4];
    private final TextView[] chainValues=new TextView[4];private TextView chainStatus,chainClear,chainRoute;
    private final Runnable chainExpiry=()->{chainExpired=true;updateChainUi();};
    private LinearLayout legacyToneRow;
    private EnvelopeState envelopeState;
    private EnvelopeModel envelopeModel;
    private EnvelopeCurveView envelopeCurve;
    private int envelopeTicket,envelopeMidi=60,envelopeTimbre,envelopeCapo,envelopeDuration=600;
    private int envelopeKnownBanks,envelopeCurveKey=-1;
    private final int[] envelopeKnownMask=new int[4],envelopeModifiedMask=new int[4];
    private final int[][] envelopeValues=new int[4][28];
    private final java.util.Map<Integer,Integer> envelopeDrafts=new java.util.HashMap<>();
    private long envelopeReceived;
    private TextView envelopeButton,envelopeStatus,envelopeReset,envelopeResetAll,envelopeNote,envelopeBankButton,envelopeModified;
    private SeekBar[] envelopeBars;
    private TextView[] envelopeLabels;
    private boolean envelopeFlushScheduled;
    private android.app.Dialog envelopeDialog;
    private final Runnable envelopeExpiry=this::updateEnvelopeUi;
    private final Runnable envelopeFlush=()->{envelopeFlushScheduled=false;flushEnvelope();};
    private final java.util.LinkedHashMap<Integer,EnvelopeEdit> envelopeEdits=new java.util.LinkedHashMap<>();
    private EnvelopeEdit envelopePending;
    private static final class EnvelopeEdit {
        final int op,bank,midi,value;
        EnvelopeEdit(int op,int bank,int midi,int value){this.op=op;this.bank=bank;this.midi=midi;this.value=value;}
    }
    private ModelState modelState;
    private TextView modelStatus;private final java.util.List<TextView> modelChoices=new ArrayList<>();
    private int modelTicket=0,pendingModel=-1;
    private String modelFeedback;private boolean modelPending=false,modelExpired=true;
    private final Runnable modelExpiry=()->{modelExpired=true;updateModelUi();};
    private final java.util.List<android.widget.Switch> fadeToggles=new ArrayList<>();
    private boolean updatingFade,guideSoft=true,guideSoftKnown;
    private int scoreCapo;
    private CapoState capoState;
    private SeekBar capoBar;
    private TextView capoLabel,capoStatus,capoApply,capoReset;
    private int capoTicket=0,confirmedCapo=0;
    private boolean capoDraftDirty;
    private com.ukulele.monitor.ui.CapoFretboardView capoBoard;
    private TextView capoActual,capoPlayable,capoMinus,capoPlus;
    private final java.util.ArrayList<TextView> capoChoices=new java.util.ArrayList<>();
    private boolean capoPending=false,capoExpired=true,capoDragging=false;
    private Runnable capoTimeout;
    private final Runnable capoExpiry=()->{capoExpired=true;updateCapoUi();};

    private View buildSettingsPage() {
        settingsIllustrations.clear();
        settingsNavigator = new SettingsNavigator(this, uiScale, id -> { settingsModuleId = id; tap(); refreshSettingsOverview(); });
        settingsNavigator.addGroup("device", "设备与演奏");
        settingsNavigator.addGroup("experience", "显示与体验");
        settingsNavigator.addGroup("app", "应用与服务");

        // ---- 快捷入口：先给最常用的三件事，避免用户在长设置页里找 ----
        LinearLayout body = settingsModule("app", "quick", "帮助与快捷入口", "使用指南 · AI 配置 · 音乐搭子", R.drawable.ic_link,
                "快速打开使用指南、AI 配置和音乐搭子。", null, null);
        LinearLayout quick = card();
        LinearLayout quickRow = row();
        TextView quickHelp = outlineAction("帮助");
        TextView quickAi = tinta("配置 AI");
        TextView quickChat = outlineAction("找搭子");
        quickRow.addView(quickHelp, new LinearLayout.LayoutParams(0, -2, 1));
        addSpace(quickRow, 8);
        quickRow.addView(quickAi, new LinearLayout.LayoutParams(0, -2, 1));
        addSpace(quickRow, 8);
        quickRow.addView(quickChat, new LinearLayout.LayoutParams(0, -2, 1));
        rowMinHeight(quickRow, 44);
        quick.addView(quickRow);
        quick.addView(hint("主题、字号与启动动画在「外观」；评分宽容在「练习」页。"));
        quickHelp.setOnClickListener(v -> { tap(); startActivity(new Intent(this, HelpActivity.class)); });
        quickAi.setOnClickListener(v -> { tap(); openAiSetup(null); });
        quickChat.setOnClickListener(v -> { tap(); openAiChat(); });
        addContent(body, quick);

        // ---- group 1: device + playing ----
        body = settingsModule("device", "connection", "设备连接", "蓝牙连接与自动重连", R.drawable.ic_bluetooth,
                "连接你的尤克里里，查看设备状态并管理自动重连。", "connection", "手机通过蓝牙连接乐器，接收演奏数据并发送设置。");
        LinearLayout connectionSettingsBody = body;
        LinearLayout conn = card();
        LinearLayout connHead = row();
        deviceStatusLabel = kit.textBold("我的尤克里里", ts(16), cTextPrimary());
        deviceStatusLabel.setMinimumHeight(dp(26));
        connHead.addView(deviceStatusLabel, new LinearLayout.LayoutParams(0, -2, 1));
        devicePillLabel = badge("未连接", cSurface3(), cTextSecondary());
        connHead.addView(devicePillLabel);
        conn.addView(connHead);
        deviceSubtitleLabel = kit.text("连接你的琴，开始练习与创作。", ts(12), cTextSecondary());
        deviceSubtitleLabel.setPadding(0, dp(6), 0, 0);
        conn.addView(deviceSubtitleLabel);
        LinearLayout connButtons = row();
        TextView connect = primaryAction("扫描并连接");
        connectBtn = connect;
        TextView dis = outlineAction("断开");
        connButtons.addView(connect, new LinearLayout.LayoutParams(0, -2, 1));
        addSpace(connButtons, 10);
        connButtons.addView(dis, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(-1, -2);
        crp.topMargin = dp(14);
        rowMinHeight(connButtons, 46); conn.addView(connButtons, crp);
        connect.setOnClickListener(v -> { tap(); scan(); });
        dis.setOnClickListener(v -> { tap(); pendingWakeAfterConnect=false;disconnect(); updateDeviceInfo(); });
        LinearLayout lastRow = column();
        lastRow.setPadding(0, dp(14), 0, 0);
        TextView lastTitle = hint("上次连接的设备"); lastTitle.setMinimumHeight(dp(26));
        lastRow.addView(lastTitle, new LinearLayout.LayoutParams(-1, -2));
        lastDeviceLabel = kit.text("尚无记录", ts(13), cTextPrimary());
        lastDeviceLabel.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        lastDeviceLabel.setMaxLines(2);
        lastDeviceLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lastRow.addView(lastDeviceLabel, new LinearLayout.LayoutParams(-1, -2));
        conn.addView(lastRow);
        LinearLayout autoRow = settingsRow("自动重连", "打开应用时自动连接上次设备", autoConnectSwitch = kit.toggle(false));
        conn.addView(autoRow);
        addContent(body, conn);

        body = settingsModule("device", "power", "电源与休眠", "唤醒乐器 · 自动休眠时间", R.drawable.ic_power,
                "设置乐器空闲后的休眠时间，需要时一键唤醒。", "power", "空闲达到设定时间后进入休眠；唤醒后可继续演奏。示意图不代表当前设备状态。");
        addContent(body, buildPowerCard(false));

        body = settingsModule("device", "gestures", "演奏手势", "滑音 · 颤音判定与门限", R.drawable.ic_gesture,
                "设置滑音、颤音判定和触发门限，结果以乐器回读为准。", "gestures", "滑音：沿同一根弦移动品位；颤音：在原品位轻揉琴弦。");
        LinearLayout gestures=card();
        glideSwitch=kit.toggle(true);vibratoSwitch=kit.toggle(true);
        gestures.addView(settingsRow("滑音", "按住同弦移动品位时平滑变音",glideSwitch));
        gestures.addView(settingsRow("颤音", "同弦揉弦时产生音高起伏",vibratoSwitch));
        vibratoSourceButton=outlineAction("颤音判定 ▾");
        vibratoSourceButton.setOnClickListener(v -> chooseVibratoSource());
        addPracticeButtonRow(gestures, 8, vibratoSourceButton);
        gestureStatus=kit.text("连接设备后读取实际开关状态",ts(12),cTextSecondary());
        gestureStatus.setPadding(0,dp(10),0,0);gestures.addView(gestureStatus);
        addVibratoThreshold(gestures);
        glideSwitch.setOnCheckedChangeListener((button,on)->requestGestureChange());
        vibratoSwitch.setOnCheckedChangeListener((button,on)->requestGestureChange());
        addContent(body,gestures);updateGestureUi();
        body = settingsModule("device", "capo", "模拟变调夹", "夹持区提示 · 真实品位联动", R.drawable.ic_gesture,
                "选择夹持位置，预览空弦音与可演奏区域。", null, "");
        addCapoControls(body);
        body = settingsModule("device", "touch", "触摸与同拨", "转换档位 · 同拨容错", R.drawable.ic_touch,
                "调整电容转换速度和换品时的同拨容错。", "touch", "稳定按品立即发声，发现换品时按设定时间等待确认。");
        addTouchControls(body);

        // ---- 光电拨弦（高级项：默认收起，减少设置页长度）----
        body = settingsModule("device", "opto", "光电拨弦", "灵敏度预设与参数回读", R.drawable.ic_lightbulb,
                "根据演奏环境选择预设，再回读实际参数确认。", "opto", "光电检测原理示意：拨动琴弦改变光路，由接收端识别拨弦动作。");
        settingsContentCard(body, box -> {
            box.addView(cardLabel("校正预设"));
            box.addView(hint("选择适合当前环境的预设，结果以设备回读为准。"));
            GridLayout presets = new GridLayout(this);
            presets.setColumnCount(2);
            presets.setUseDefaultMargins(false);
            String[] presetNames = {"灵敏", "均衡", "抗误报", "环境光"};
            for (int i = 0; i < presetNames.length; ++i) {
                final int which = i;
                TextView b = outlineAction(presetNames[i]);
                presetButtons[i] = b;
                b.setOnClickListener(v -> { tap(); writeCommand("OPTO_PRESET:" + which); toast("已发送：" + presetNames[which]); });
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = 0; lp.height = -2;
                lp.columnSpec = GridLayout.spec(i % 2, 1, 1);
                lp.rowSpec = GridLayout.spec(i / 2);
                lp.setMargins(0, dp(8), i % 2 == 0 ? dp(10) : 0, 0);
                presets.addView(b, lp);
            }
            box.addView(presets);
            presetStatusText = hint("当前预设：等待回读");
            presetStatusText.setPadding(0, dp(10), 0, 0);
            box.addView(presetStatusText);
            TextView get = tinta("回读当前参数");
            get.setOnClickListener(v -> { tap(); writeCommand("OPTO_GET"); });
            LinearLayout.LayoutParams getLp = new LinearLayout.LayoutParams(-1, -2);
            getLp.topMargin = dp(10);
            box.addView(get, getLp);
        });

        // ---- 保存与恢复（高级项：默认收起，避免误触设备配置）----
        body = settingsModule("device", "storage", "保存与恢复", "保存配置 · 载入配置 · 恢复默认", R.drawable.ic_save,
                "将乐器当前参数保存为启动配置，或载入之前保存的设置。", "storage", "保存将当前参数写入设备；载入将已保存配置恢复到当前参数。");
        settingsContentCard(body, box -> {
            operationStatusText = hint("等待实际操作结果");
            operationStatusText.setPadding(0, 0, 0, dp(4));
            box.addView(operationStatusText);
            String[] labels = {"保存实际配置", "载入已保存配置", "临时应用默认", "默认并保存（一拍锁定）"};
            String[] commands = {"OPTO_SAVE", "OPTO_LOAD", "OPTO_DEFAULT", "OPTO_FACTORY"};
            for (int i = 0; i < labels.length; i++) {
                final String command = commands[i];
                TextView b = outlineAction(labels[i]);
                b.setOnClickListener(v -> {
                    tap();
                    if (command.equals("OPTO_FACTORY")) AppDialogs.message(this,"保存为启动配置","将团队默认参数及一拍锁定模式保存为启动配置。是否继续？","应用并保存",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){writeCommand(command);}},"取消",null,null,null);
                    else writeCommand(command);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(8);
                box.addView(b, lp);
            }
        });

        // ---- 外观 ----

        // ---- group 2: practice + display ----
        body = settingsModule("experience", "display", "演奏显示", "指法提示与音符显示方式", R.drawable.ic_text,
                "选择你更熟悉的音符表示方式，下方画面会同步预览。", "display", "预览随音名、唱名、品位数字及指法提示开关即时更新；示例音符仅用于展示。");
        LinearLayout display = card();
        showFingeringSwitch = kit.toggle(false);
        display.addView(settingsRow("显示指法提示", "拨弦前先提示按弦位置", showFingeringSwitch));
        display.addView(settingLabel("音符显示方式", dp(16)));
        LinearLayout noteRow = row();
        String[] noteNames = {"音名 C D E", "唱名 do re mi", "品位数字"};
        noteStyleButtons = new TextView[noteNames.length];
        for (int i = 0; i < noteNames.length; ++i) {
            final int style = i;
            noteStyleButtons[i] = segment(noteNames[i], false, i, noteNames.length);
            // 直接在这里落一次选中态，不依赖 SettingsStore 的回调链：
            // 只要用户能看见控件被点亮/切换，显示方式就确实生效了。
            noteStyleButtons[i].setOnClickListener(v -> {
                tap();
                settings.setNoteStyle(style);
                refreshNoteStyleButtons();
            });
            noteRow.addView(noteStyleButtons[i], new LinearLayout.LayoutParams(0, -2, 1));
            if (i < noteNames.length - 1) addSpace(noteRow, 8);
        }
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(10);
        rowMinHeight(noteRow, 42); display.addView(noteRow, noteLp);
        keepScreenOnSwitch = kit.toggle(false);
        display.addView(settingsRow("演奏时屏幕常亮", "连接设备或跟弹时保持屏幕不熄灭", keepScreenOnSwitch));
        addContent(body, display);

        // ---- 反馈 ----
        body = settingsModule("experience", "feedback", "反馈", "震动反馈与操作提示音", R.drawable.ic_vibrate,
                "为切换模式、选中曲目和发送指令设置操作反馈。", "feedback", "图中震动与提示音状态随开关即时更新。");
        LinearLayout feedbackCard = card();
        hapticsSwitch = kit.toggle(false);
        feedbackCard.addView(settingsRow("震动反馈", "切换模式、灯阵与选中曲目时轻微震动", hapticsSwitch));
        soundSwitch = kit.toggle(false);
        feedbackCard.addView(settingsRow("提示音", "发送指令时播放轻提示音", soundSwitch));
        addContent(body, feedbackCard);

        // ---- 外观 ----
        body = settingsModule("experience", "appearance", "外观", "主题 · 界面字号 · 启动动画", R.drawable.ic_palette,
                "调整整个 App 的主题和字号，选择后立即应用。", "appearance", "图示对比深色与浅色主题，当前主题和字号以强调色标示。跟随系统时随系统外观切换。");
        LinearLayout appearance = card();
        appearance.addView(settingLabel("主题"));
        AppTheme.Style[] styles = AppTheme.Style.values();
        for (int first = 0; first < styles.length; first += 2) {
            LinearLayout choices = row();
            for (int i = first; i < Math.min(first+2,styles.length); i++) {
                AppTheme.Style style = styles[i];
                TextView tile = outlineAction(style.label + (style.id.equals(settings.themeMode()) ? " ✓" : ""));
                tile.setBackground(kit.bg(style.preview.surface, 14, style.preview.accent, dp(1)));
                tile.setTextColor(style.preview.accent);
                tile.setContentDescription(style.label + (style.id.equals(settings.themeMode()) ? "，当前主题" : ""));
                tile.setOnClickListener(v -> { tap(); settings.setThemeMode(style.id); });
                if(i>first)addSpace(choices,8);
                choices.addView(tile,new LinearLayout.LayoutParams(0,dp(48),1));
            }
            LinearLayout.LayoutParams tiles = new LinearLayout.LayoutParams(-1,-2);
            tiles.topMargin=dp(8); appearance.addView(choices,tiles);
        }
        appearance.addView(settingLabel("界面字号", dp(16)));
        LinearLayout scaleRow = row();
        String[] scaleNames = {"小", "标准", "大"};
        textScaleButtons = new TextView[scaleNames.length];
        for (int i = 0; i < scaleNames.length; ++i) {
            final int scale = i;
            textScaleButtons[i] = segment(scaleNames[i], false, i, scaleNames.length);
            // 字号切换同样会重建界面；这里只保证点击即时可见。
            textScaleButtons[i].setOnClickListener(v -> { tap(); settings.setTextScale(scale); updateTextScaleButtons(); });
            scaleRow.addView(textScaleButtons[i], new LinearLayout.LayoutParams(0, -2, 1));
            if (i < scaleNames.length - 1) addSpace(scaleRow, 8);
        }
        LinearLayout.LayoutParams scaleLp = new LinearLayout.LayoutParams(-1, -2);
        scaleLp.topMargin = dp(10);
        rowMinHeight(scaleRow, 42); appearance.addView(scaleRow, scaleLp);
        appearance.addView(hint("字号会即时应用到所有页面，曲目列表与设置项同步放大。"));
        splashSwitch = kit.toggle(settings.splashEnabled());
        appearance.addView(settingsRow("启动加载动画", "打开应用时播放约 2 秒的品牌动画，轻触可跳过", splashSwitch));
        addContent(body, appearance);

        // ---- v3.12.0：AI 与音乐搭子 ----
        body = settingsModule("app", "ai", "AI 与音乐搭子", "DeepSeek 配置与语音聊天", R.drawable.ic_sparkle,
                "管理 AI 通道、个人 API Key 与音乐搭子。", null, null);
        LinearLayout aiCard = card();
        aiStatusLabel = kit.text("", ts(13), cTextSecondary());
        aiCard.addView(aiStatusLabel);
        aiCard.addView(hint("个人 API Key 只保存在本机（加密存储），请求直接发往 api.deepseek.com；官方通道需输入口令。语音对话会申请麦克风权限，可随时关闭。最多可养 4 个音乐搭子，聊音乐、生活、灵感都行。"));
        TextView aiSetupButton = primaryAction("配置 AI / API Key");
        aiSetupButton.setOnClickListener(v -> { tap(); openAiSetup(null); });
        LinearLayout.LayoutParams aiSetupLp = new LinearLayout.LayoutParams(-1, -2);
        aiSetupLp.topMargin = dp(12);
        aiCard.addView(aiSetupButton, aiSetupLp);
        TextView aiChatButton = tinta("和音乐搭子聊聊");
        aiChatButton.setOnClickListener(v -> { tap(); openAiChat(); });
        LinearLayout.LayoutParams aiChatLp = new LinearLayout.LayoutParams(-1, -2);
        aiChatLp.topMargin = dp(8);
        aiCard.addView(aiChatButton, aiChatLp);
        voicePackStatus=kit.text(com.ukulele.monitor.ai.VoiceModelPack.status(this),ts(12),cTextSecondary());voicePackStatus.setPadding(0,dp(12),0,dp(6));aiCard.addView(voicePackStatus);
        TextView voicePack=outlineAction("导入离线中文语音包");voicePack.setOnClickListener(v->{if(voicePackImporting){toast("语音包正在导入");return;}Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.setType("*/*");pick.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(pick,REQ_VOICE_PACK);}catch(Exception e){toast("没有可用的文件选择器");}});aiCard.addView(voicePack,new LinearLayout.LayoutParams(-1,-2));
        addContent(body, aiCard);

        // ---- 演奏显示 ----

        // ---- group 4: app ----
        body = settingsModule("app", "behavior", "使用偏好", "记住上次选择的曲目", R.drawable.ic_settings,
                "设置下次打开 App 时是否恢复上一次选中的曲目。", null, null);
        LinearLayout behaviour = card();
        rememberSongSwitch = kit.toggle(false);
        behaviour.addView(settingsRow("记住上次曲目", "重新打开应用时恢复上次选择的曲目", rememberSongSwitch));
        addContent(body, behaviour);

        // ---- 说明 ----
        LinearLayout helpCard = card();
        helpCard.addView(cardLabel("连接提示"));
        connectionHelpLabel = kit.text("未连接时请确认 ESP32 已上电，并在手机设置中允许本应用使用蓝牙。", ts(12), cTextSecondary());
        connectionHelpLabel.setPadding(0, dp(6), 0, 0);
        helpCard.addView(connectionHelpLabel);
        // 旧版这张卡创建后一直没挂到设置页上（不可见）；重排后放回「应用与关于」组。
        addContent(connectionSettingsBody, helpCard);

        // ---- 关于 ----
        body = settingsModule("app", "about", "关于本应用", "版本信息与使用指南", R.drawable.ic_info,
                "Ukulele Studio，你的尤克里里练习伙伴。", null, null);
        LinearLayout about = card();
        about.addView(kit.textBold("Ukulele Studio", ts(16), cTextPrimary()));
        TextView aboutHelp = outlineAction("打开帮助与指南");
        aboutHelp.setOnClickListener(v -> { tap(); startActivity(new Intent(this, HelpActivity.class)); });
        LinearLayout.LayoutParams aboutHelpLp = new LinearLayout.LayoutParams(-1, -2);
        aboutHelpLp.topMargin = dp(12);
        about.addView(aboutHelp, aboutHelpLp);
        versionLabel = kit.text("你的尤克里里练习伙伴 · v" + AppVersion.NAME, ts(13), cTextSecondary());
        versionLabel.setPadding(0, dp(4), 0, 0);
        about.addView(versionLabel);
        TextView eggHint = kit.text("小彩蛋：长按左上角 Logo 试试。", ts(12), cTextTertiary());
        eggHint.setPadding(0, dp(10), 0, 0);
        about.addView(eggHint);

        TextView sources=outlineAction("练习曲库与开源致谢");sources.setOnClickListener(v->{String text;try(java.io.InputStream in=getAssets().open("licenses/Practice-Catalog.txt")){java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);text=out.toString("UTF-8");}catch(Exception error){text="曲库来源：OpenScore Lieder（CC0）及传统主题自主练习编配。";}new android.app.AlertDialog.Builder(this).setTitle("曲库来源与编配").setMessage(text).setPositiveButton("关闭",null).show();});about.addView(sources,new LinearLayout.LayoutParams(-1,-2));
        addContent(body, about);


        updateTextScaleButtons();
        updateNoteStyleButtons();
        syncSettingsControls();
        bindSwitches();
        updateDeviceInfo();
        refreshSettingsOverview();
        settingsNavigator.showModule(settingsModuleId, false);
        settingsModuleId = settingsNavigator.currentModule();
        return settingsNavigator;
    }

    /** 设置项里的小标题。 */
    private TextView settingLabel(String value) { return settingLabel(value, 0); }

    /** 可折叠设置卡的内容填充器（避免为每个高级分组写一套 show/hide）。 */
    private interface SettingsContent { void fill(LinearLayout box); }

    /** Advanced settings are fully visible inside their own detail page. */
    private void settingsContentCard(LinearLayout body, SettingsContent content) {
        LinearLayout box = card(); content.fill(box); addContent(body, box);
    }

    private LinearLayout settingsModule(String group, String id, String title, String summary,
                                       int icon, String description, String imageKind, String caption) {
        SettingsIllustrationView picture = null;
        if (imageKind != null) {
            picture = new SettingsIllustrationView(this, imageKind, settings);
            settingsIllustrations.add(picture);
        }
        int color = "device".equals(group) ? cAccent() : "experience".equals(group) ? cWarn() : cSuccess();
        return settingsNavigator.addModule(group, id, title, summary, icon, color, description, picture, caption);
    }

    private void refreshSettingsOverview() {
        if (settingsNavigator == null || settings == null) return;
        settingsNavigator.setSummary("connection", bleReady ? "已连接 · " + settings.lastDeviceName() : scanning ? "正在扫描附近的乐器…" : "未连接 · 蓝牙与自动重连");
        settingsNavigator.setSummary("power", powerState == null ? "唤醒乐器 · 自动休眠时间" : powerState.label());
        if (gestureState != null && gestureState.fresh() && !gestureExpired)
            settingsNavigator.setSummary("gestures", "滑音" + ((gestureState.actual & 1) != 0 ? "开" : "关") + " · 颤音" + ((gestureState.actual & 2) != 0 ? "开" : "关"));
        else settingsNavigator.setSummary("gestures", "滑音与颤音 · 连接后读取");
        settingsNavigator.setSummary("display", new String[]{"音名 C D E", "唱名 do re mi", "品位数字"}[settings.noteStyle()] + (settings.showFingering() ? " · 显示指法" : " · 隐藏指法"));
        settingsNavigator.setSummary("appearance", AppTheme.Style.fromId(settings.themeMode()).label + " · " + new String[]{"小字号", "标准字号", "大字号"}[settings.textScale()]);
        settingsNavigator.setSummary("feedback", "震动" + (settings.hapticsEnabled() ? "开" : "关") + " · 提示音" + (settings.soundEnabled() ? "开" : "关"));
        settingsNavigator.setSummary("behavior", settings.rememberLastSong() ? "已开启 · 记住上次曲目" : "已关闭 · 不恢复上次曲目");
        settingsNavigator.setSummary("about", "Ukulele Studio · v" + AppVersion.NAME);
        for (SettingsIllustrationView picture : settingsIllustrations) picture.invalidate();
    }

    /**
     * 设置页的分组标题。
     *
     * 设置页原本是 9 个平级小节一字排开（设备连接 → 演奏手势 → 光电拨弦 → 保存与恢复 →
     * 外观 → 演奏显示 → 反馈 → 行为 → 关于），从「乐器硬件」到「换个主题」混在一起，
     * 想找一项只能一路滑到底。现在按用途分成五组（设备与演奏 · 练习与显示 · 外观 ·
     * AI 与音乐搭子 · 应用与关于），低频高级项默认折叠。
     */
    private TextView groupTitle(String value, int topMarginDp) {
        TextView v = kit.textBold(value, ts(13), cAccent());
        v.setLetterSpacing(0.08f);
        v.setPadding(0, dp(topMarginDp), 0, 0);
        return v;
    }

    private TextView settingLabel(String value, int topMarginDp) {
        TextView v = kit.textBold(value, ts(14), cTextPrimary());
        v.setPadding(0, dp(topMarginDp), 0, 0);
        return v;
    }

    /** 设置行：左标题 + 副标题，右侧开关。 */
    private LinearLayout settingsRow(String title, String subtitle, View trailing) {
        LinearLayout r = row();
        r.setPadding(0, dp(12), 0, dp(12));
        r.setMinimumHeight(dp(56));
        LinearLayout texts = column();
        texts.addView(kit.text(title, ts(14), cTextPrimary()));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = kit.text(subtitle, ts(12), cTextSecondary());
            sub.setPadding(0, dp(3), 0, 0);
            texts.addView(sub);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.leftMargin = dp(12);
            r.addView(trailing, tp);
        }
        return r;
    }

    // ---- 设置控件状态同步 ----
    private void updateTextScaleButtons() {
        if (textScaleButtons == null || settings == null) return;
        for (int i = 0; i < textScaleButtons.length; ++i) setSegment(textScaleButtons[i], i == settings.textScale());
    }

    private void updateNoteStyleButtons() {
        if (noteStyleButtons == null || settings == null) return;
        int current = settings.noteStyle();
        for (int i = 0; i < noteStyleButtons.length; ++i) setSegment(noteStyleButtons[i], i == current);
    }

    /** 三选一控件：无论设置是否真的发生变化，都把选中态刷成当前值。 */
    private void refreshSelected(TextView[] buttons, int selected) {
        if (buttons == null) return;
        for (int i = 0; i < buttons.length; ++i) setSegment(buttons[i], i == selected);
    }

    private void refreshNoteStyleButtons() {
        if (settings == null) return;
        refreshSelected(noteStyleButtons, settings.noteStyle());
    }

    private void syncSettingsControls() {
        if (settings == null) return;
        if (showFingeringSwitch != null) showFingeringSwitch.setChecked(settings.showFingering());
        if (keepScreenOnSwitch != null) keepScreenOnSwitch.setChecked(settings.keepScreenOn());
        if (hapticsSwitch != null) hapticsSwitch.setChecked(settings.hapticsEnabled());
        if (soundSwitch != null) soundSwitch.setChecked(settings.soundEnabled());
        if (autoConnectSwitch != null) autoConnectSwitch.setChecked(settings.autoConnect());
        if (rememberSongSwitch != null) rememberSongSwitch.setChecked(settings.rememberLastSong());
        if (splashSwitch != null) splashSwitch.setChecked(settings.splashEnabled());
    }

    private void bindSwitches() {
        bindSwitch(showFingeringSwitch, () -> settings.showFingering(), v -> settings.setShowFingering(v));
        bindSwitch(keepScreenOnSwitch, () -> settings.keepScreenOn(), v -> settings.setKeepScreenOn(v));
        bindSwitch(hapticsSwitch, () -> settings.hapticsEnabled(), v -> {
            settings.setHapticsEnabled(v);
            if (v && feedback != null) feedback.tap();
        });
        bindSwitch(soundSwitch, () -> settings.soundEnabled(), v -> {
            settings.setSoundEnabled(v);
            if (v && feedback != null) feedback.confirm();
        });
        bindSwitch(autoConnectSwitch, () -> settings.autoConnect(), v -> {
            settings.setAutoConnect(v);
            updateDeviceInfo();
        });
        bindSwitch(rememberSongSwitch, () -> settings.rememberLastSong(), v -> {
            settings.setRememberLastSong(v);
            if (!v) { settings.setLastSongId(""); settings.setLastSongName(""); }
        });
        bindSwitch(splashSwitch, () -> settings.splashEnabled(), v -> settings.setSplashEnabled(v));
    }

    private interface SwitchGetter { boolean get(); }
    private interface SwitchValueSink { void onChecked(boolean checked); }

    /**
     * 绑定开关。每次回调都拿开关的实际状态与**当前**设置值比较（不缓存绑定时的旧值，
     * 否则第一次拨动后基值过期，第二次拨回会被误判为“无变化”而不写入）。
     * 值相同则不重复写盘，SettingsStore 也不会发出无意义的事件。
     */
    private void bindSwitch(Switch button, final SwitchGetter current, final SwitchValueSink sink) {
        if (button == null) return;
        button.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (checked == current.get()) return;
                sink.onChecked(checked);
            }
        });
    }

    // ---- 设备信息 ----
    private void updateDeviceInfo() {
        String name = settings == null ? "" : settings.lastDeviceName();
        String address = settings == null ? "" : settings.lastDeviceAddress();
        if (lastDeviceLabel != null) lastDeviceLabel.setText(address.isEmpty() ? "尚无记录" : (name.isEmpty() ? address : name + " · " + address));
        if (deviceSubtitleLabel != null) {
            if (gatt != null && bleReady) deviceSubtitleLabel.setText("已连接，正在接收波形与曲谱状态。");
            else if (scanning) deviceSubtitleLabel.setText("正在扫描附近的 Ukulele 设备…");
            else if (address.isEmpty()) deviceSubtitleLabel.setText("连接你的琴，开始练习与创作。");
            else deviceSubtitleLabel.setText("已记录上次设备，可点“扫描并连接”重新连接。");
        }
        if (devicePillLabel != null) {
            String text;
            int bg;
            int fg;
            if (gatt != null && bleReady) { text = "已连接"; bg = cAccentSoft(); fg = cAccent(); }
            else if (scanning || gatt != null) { text = "连接中"; bg = cSurface3(); fg = cWarn(); }
            else { text = "未连接"; bg = cSurface3(); fg = cTextSecondary(); }
            devicePillLabel.setText(text);
            devicePillLabel.setBackground(kit.bg(bg, 999));
            devicePillLabel.setTextColor(fg);
        }
        if (connectBtn != null) {
            boolean busy = scanning || (gatt != null && !bleReady);
            connectBtn.setText(busy ? "连接中…" : "扫描并连接");
        }
        refreshSettingsOverview();
    }

    // ---- 自动重连 ----
    /**
     * 自动重连：仍然走基线的「按名字扫描」流程，不额外放宽过滤条件，
     * 因此不会误连非 Ukulele 设备。lastDeviceAddress 只用于界面展示上次设备。
     */
    private void autoConnectIfWanted() {
        if (settings == null || !settings.autoConnect() || adapter == null) return;
        if (!permissionsOk()) return;
        if (gatt != null) return;
        scan();
    }

    /** 连接成功后记录设备，供下次自动重连与设置页展示。 */
    private void rememberDevice(BluetoothDevice device, String name) {
        if (settings == null || device == null) return;
        String address = null;
        try { address = device.getAddress(); } catch (SecurityException ignored) { }
        if (address == null || address.isEmpty()) return;
        settings.setLastDeviceAddress(address);
        if (name != null && !name.isEmpty()) settings.setLastDeviceName(name);
        else {
            try { settings.setLastDeviceName(device.getName() == null ? "" : device.getName()); } catch (SecurityException ignored) { }
        }
    }

    private boolean permissionsOk() {
        if (Build.VERSION.SDK_INT >= 31) return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void scan() { scan(false); }

    /**
     * @param fromAutoReconnect 由自动重连发起时为 true：此时不能取消待定的重连计划，
     *        并且扫描失败后由重连流程自己排下一次（次数上限由 MAX_RECONNECT_ATTEMPTS 控制）。
     */
    private void scan(boolean fromAutoReconnect) {
        if (adapter == null) { toast("手机没有蓝牙适配器"); return; }
        if (!permissionsOk()) { requestPermissions(Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT} : new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERM); return; }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner(); if (scanner == null) { toast("蓝牙扫描不可用"); return; }
        // 允许「再次点击扫描」直接重启扫描：先把上一轮的扫描与它的 10 秒超时都撤掉。
        // 若只 stopScan 而不撤超时，上一轮的定时器会在新扫描进行中触发并把新扫描停掉。
        if (scanTimeout != null) { main.removeCallbacks(scanTimeout); scanTimeout = null; }
        if (scanning && oldScanner != null) { try { oldScanner.stopScan(scanCallback); } catch (Exception ignored) {} }
        scanning = false;
        if (fromAutoReconnect) disconnectInternal(); else disconnect();
        scanning = true;
        oldScanner = scanner;
        statusReliable = false;
        setStatusText("正在扫描…");
        updateDeviceInfo();
        scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        final int generation = ++scanGeneration;
        scanTimeout = () -> {
            // 只处理「自己那一次」扫描的超时，避免旧定时器误停新扫描。
            if (generation != scanGeneration) return;
            if (!scanning) return;
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
            scanning = false;
            pendingWakeAfterConnect=false;setStatusText("未找到设备");
            updateDeviceInfo();
            if (fromAutoReconnect) scheduleAutoReconnect(false);
        };
        main.postDelayed(scanTimeout, 10000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice(); String n = null;
            try { n = d.getName(); } catch (SecurityException ignored) {}
            // 过滤条件与基线一致：只认名字里带 Ukulele 的设备（自动重连同样走这条路）。
            if (scanning && n != null && n.contains("Ukulele")) {
                scanning = false;
                try {
                    adapter.getBluetoothLeScanner().stopScan(this);
                    gatt = d.connectGatt(MainActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                    setStatusText("正在连接…");
                    rememberDevice(d, n);
                    updateDeviceInfo();
                    armBleTimeout(gatt, "连接设备");
                } catch (SecurityException e) { toast("缺少蓝牙连接权限"); }
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            main.post(() -> {
                if (g != gatt) { g.close(); return; }
                // 先处理「已断开」：掉线时 st 通常是 GATT 错误码（8/19/22/133 等），
                // 若先判 st != SUCCESS 就会把正常掉线报成「连接失败」。断开与连接失败
                // 都要走自动重连，但文案与状态色不同。
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean wasStable = connectionWasStable();
                    disconnectInternal();          // 会清 bleReady / bleReadySince，所以稳定性必须先算
                    updateDeviceInfo();
                    if (st != BluetoothGatt.GATT_SUCCESS) setStatusText("连接已断开（状态 " + st + "）");
                    else setStatusText("已断开");
                    scheduleAutoReconnect(wasStable);
                    return;
                }
                if (st != BluetoothGatt.GATT_SUCCESS) { failBle("蓝牙连接失败，状态 " + st); return; }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setStatusText("发现服务…");
                    armBleTimeout(g, "发现服务");
                    // Discover first; MTU and descriptor writes must not overlap it.
                    if (!g.discoverServices()) main.postDelayed(() -> { if (g == gatt && !g.discoverServices()) failBle("无法启动服务发现，请重新连接"); }, 400);
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            main.post(() -> {
                if (g != gatt) return;
                if (st != BluetoothGatt.GATT_SUCCESS) { failBle("服务发现失败，状态 " + st); return; }
                BluetoothGattService s = g.getService(SERVICE);
                if (s == null) { failBle("设备没有尤克里里服务，请更新 ESP32 固件"); return; }
                for (UUID u : new UUID[]{COMMAND, SONG, STATUS, EVENT, WAVE, SPECTRUM, SONG_STATUS}) {
                    if (s.getCharacteristic(u) == null) { failBle("ESP32 蓝牙服务不完整，请更新固件后重新连接"); return; }
                }
                commandChar = s.getCharacteristic(COMMAND); songChar = s.getCharacteristic(SONG); settingsChar=s.getCharacteristic(STATUS);
                notifyQueue.clear();
                for (UUID u : new UUID[]{STATUS, EVENT, WAVE, SPECTRUM, SONG_STATUS}) {
                    BluetoothGattCharacteristic c = s.getCharacteristic(u); BluetoothGattDescriptor d = c.getDescriptor(CCCD);
                    if (d == null || !g.setCharacteristicNotification(c, true)) { failBle("无法订阅设备数据"); return; }
                    notifyQueue.add(d);
                }
                setStatusText("协商数据长度…"); armBleTimeout(g, "协商数据长度");
                mtuSettled=false;
                if (!g.requestMtu(247)) settleAudioMtu(g,23);
                else main.postDelayed(()->{if(g==gatt&&!mtuSettled)settleAudioMtu(g,23);},2000);
            });
        }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) {
            main.post(() -> { if (g != gatt) return; settleAudioMtu(g,st==BluetoothGatt.GATT_SUCCESS?mtu:23); });
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            main.post(() -> { if (g != gatt) return; if (st != BluetoothGatt.GATT_SUCCESS) { failBle("数据订阅失败，状态 " + st); return; } notificationsBusy = false; notifyQueue.poll(); armBleTimeout(g, "订阅数据"); sendNextNotification(); });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) { if (g == gatt) parseNotify(c.getUuid(), c.getValue()); }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) { if (g == gatt) parseNotify(c.getUuid(), value); }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            receiveSettingsRead(g,c,c.getValue(),st);
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int st) {
            receiveSettingsRead(g,c,value,st);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            main.post(() -> {
                if (g != gatt) return;
                if (writeTimeout != null) { main.removeCallbacks(writeTimeout); writeTimeout = null; }
                writeWatchdogArmed = false;
                if (st != BluetoothGatt.GATT_SUCCESS) { failBle("发送失败，状态 " + st); return; }
                if (c.getUuid().equals(SONG)) {
                    songQueue.poll();
                    if (songQueue.isEmpty() && pendingUploadId != null) {
                        uploadTransportDone=true;
                        finishSongUpload();
                        if(pendingUploadId!=null){
                            final String expected=pendingUploadId;
                            uploadAckTimeout=()->{if(expected.equals(pendingUploadId)){
                                fullGuideAfterUploadId=null;guideUploadGeneration++;pendingUploadId=null;pendingUploadName=null;uploadedSongId=null;
                                songStatusLabel.setText("设备确认超时，请重新上传");updateSongPlayState();
                            }};
                            main.postDelayed(uploadAckTimeout,4000);
                        }
                    }
                } else if (c.getUuid().equals(COMMAND)) {
                    String command = commandQueue.poll();
                    if(command!=null && (command.startsWith("SP1:0,")||command.startsWith("SP1:5,"))){
                        // GATT write completion is transport-only. Real start/rate/position
                        // comes from the token-correlated ESP32 0x91 response.
                        activePlayToken=0;activePlayId=null;
                    } else if ("SONG_PLAY".equals(command)) {
                        // 即使本地意图已取消，已写出的旧指令仍可能被设备执行，等待后续 STOP。
                        hardwarePlayingId = activePlayId;
                        // 取消后的旧写回调不能启动/清除之后的同曲重播意图。
                        if (activePlayToken == queuedPlayToken) {
                            if (queuedPlayId != null && queuedPlayId.equals(activePlayId) && queuedPlayId.equals(selectedSongId) && canPlaySelectedSong()) {
                                hardwarePlayingId = queuedPlayId;if(!songTempoSupported)guideSpeed=1d; startSongGuide(true);startScoringSession(0);
                            }
                            queuedPlayId = null;
                        }
                        activePlayToken = 0; activePlayId = null;
                    } else if ("SONG_STOP".equals(command)) {
                        if (!guideRunning) songGuideModeLabel.setText("停止指令已发送 · 设备停止状态未确认");
                    }
                }
                activeWrite = null; songWriteBusy = false; writeStartRetries = 0; updateSongPlayState();
                // 看门狗在「完成回调」里就重新计时，保证后续即使异常也不会漏掉重新计时。
                if (c.getUuid().equals(SONG)) main.postDelayed(MainActivity.this::sendNextSongPacket, 20);
                else main.post(MainActivity.this::sendNextSongPacket);
                // 写入进度看门狗：从**最近一次成功写完**开始计时。
                // 只有连续 12 秒一包都没写完（队列里还有待发数据、且当次写入始终没结束）
                // 才判定链路卡死；因此长上传不会因为包数多而被误判超时。
                armWriteProgressTimeout();
            });
        }
    };

    private void armBleTimeout(BluetoothGatt g, String stage) {
        if (bleTimeout != null) main.removeCallbacks(bleTimeout);
        bleTimeout = () -> { if (g == gatt && !bleReady) failBle(stage + "超时，请确认 ESP32 已烧录新版固件后重连"); };
        main.postDelayed(bleTimeout, 12000);
    }

    private void failBle(String reason) {
        // 连接/订阅阶段失败也交给自动重连兜一次（设备刚上电、距离远时很常见，
        // 用户不必手动点“扫描并连接”）。次数上限由 scheduleAutoReconnect 控制。
        boolean wasStable = connectionWasStable();
        disconnectInternal();
        setFailureStatus("连接失败");
        toast(reason);
        scheduleAutoReconnect(wasStable);
    }

    private void sendNextNotification() {
        if (gatt == null || notificationsBusy) return;
        BluetoothGattDescriptor d = notifyQueue.peek();
        if (d == null) {
            bleReady = true;
            bleReadySince = SystemClock.uptimeMillis();
            if (bleTimeout != null) main.removeCallbacks(bleTimeout);
            setConnectionStatus(settingsSynced ? "已连接 · 设置已同步" : "已连接 · 同步设置中…");
            updateDeviceInfo();
            setControlsEnabled(true);
            unlockBadge(ACH_FIRST_CONNECT);
            try{gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);}catch(SecurityException ignored){}
            final BluetoothGatt expected=gatt;
            if(commandChar!=null){
                commandQueue.offerConnectionQueries();
                sendNextSongPacket();
            }
            main.postDelayed(()->{if(gatt==expected && bleReady)requestAnalysisMode();},1000);
            stateSyncRetry=new Runnable(){int attempts;
                @Override public void run(){
                    if(gatt!=expected || !bleReady || settingsSynced || attempts++>=3)return;
                    if(settingsChar!=null && (settingsChar.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)!=0){
                        if(commandQueue.offer(READ_SETTINGS,songWriteBusy && activeWrite==commandChar))sendNextSongPacket();
                    }else writeCommand("STATE");
                    main.postDelayed(this,1200);
                }
            };
            main.postDelayed(stateSyncRetry,1200);
            stateSyncTimeout = () -> { if (bleReady && !settingsSynced) {
                setConnectionStatus("已连接 · 设置未回读，控制可用");
                modeStatusText.setText("模式尚未确认，请更新配套 ESP32 固件");
            } };
            main.postDelayed(stateSyncTimeout, 4500);
            return;
        }
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        notificationsBusy = gatt.writeDescriptor(d);
        if (!notificationsBusy) failBle("无法启动数据订阅，请重新连接");
    }

    private void receiveSettingsRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int status){
        final byte[] snapshot=value==null?null:value.clone();
        main.post(()->{
            if(g!=gatt || c!=activeWrite || !READ_SETTINGS.equals(commandQueue.peek()))return;
            if(writeTimeout!=null)main.removeCallbacks(writeTimeout);
            writeTimeout=null;writeWatchdogArmed=false;
            commandQueue.poll();activeWrite=null;songWriteBusy=false;writeStartRetries=0;
            if(status==BluetoothGatt.GATT_SUCCESS){
                ControllerState state=ControllerState.parse(snapshot);
                if(state!=null)applyControllerState(state);
            }
            sendNextSongPacket();
            updateSongPlayState();
        });
    }

    private void parseNotify(UUID u, byte[] d) {
        if (d == null) return;
        final BluetoothGatt origin=gatt;
        if(u.equals(STATUS) && d.length==8 && (d[0]&255)==0xb4){
            BatteryState value=BatteryState.parse(d);
            if(value!=null)main.post(()->{
                if(origin==null || origin!=gatt)return;
                batteryState=value;
                main.removeCallbacks(batteryExpired);main.postDelayed(batteryExpired,6500);
                updateBatteryUi();
                if(!selectedAnalysisSupported){selectedAnalysisSupported=true;requestAnalysisMode();}
            });
            return;
        }
        if(u.equals(STATUS) && d.length==20 && (d[0]&255)==0xA7){
            LedBitmapState b=bitmapAssembler.accept(d,android.os.SystemClock.elapsedRealtime());
            if(b!=null)main.post(()->{if(origin!=null&&origin==gatt)applyBitmapState(b);});return;
        }
        if(u.equals(STATUS) && d.length==20 && (d[0]&255)==0xA2){
            final PowerState value=PowerState.parse(d);
            if(value!=null)main.post(()->{if(origin!=null && origin==gatt)applyPowerState(value);});
            return;
        }

        if(u.equals(STATUS)&&d.length==20&&(d[0]&255)==0xAE){final ChainState value=ChainState.parse(d);if(value!=null)main.post(()->{if(origin==gatt)receiveChain(value);});return;}
        if(u.equals(STATUS)&&d.length==14&&(d[0]&255)==0xB5){
            final EnvelopeState value=EnvelopeState.parse(d);
            if(value!=null)main.post(()->{if(origin!=null&&origin==gatt)receiveEnvelope(value);});
            return;
        }
        if(u.equals(STATUS)&&d.length==13&&(d[0]&255)==0xAD){
            final ModelState value=ModelState.parse(d);if(value!=null)main.post(()->{if(origin==gatt)receiveModel(value);});return;
        }
        if(u.equals(STATUS)&&d.length==5&&(d[0]&255)==0xAC){
            int crc=0;for(byte b:d)crc^=b&255;
            if(d[1]==1&&(d[2]==0||d[2]==1)&&crc==0){final boolean soft=d[2]==1;main.post(()->{if(origin==gatt){guideSoft=soft;guideSoftKnown=true;updateGuideFadeUi();}});}return;
        }
        if(u.equals(STATUS) && d.length==13 && (d[0]&255)==0xAB){
            CapoState value=CapoState.parse(d);
            if(value!=null)main.post(()->{if(origin!=null&&origin==gatt)applyCapoState(value);});return;
        }
        if(u.equals(STATUS) && d.length==16 && (d[0]&255)==0xA6){
            FxState value=FxState.parse(d);
            if(value!=null)main.post(()->{if(origin!=null&&origin==gatt)applyFxState(value);});
            return;
        }
        if(u.equals(STATUS) && d.length==20 && (d[0]&255)==0xA1){
            final LiveNoteState notes=LiveNoteState.parse(d);
            if(notes!=null)main.post(()->{
                if(origin==null || origin!=gatt)return;
                String played=notes.newPlayedNotes(liveNoteState),fingers=notes.changedFingers(liveNoteState,activeCapo());
                boolean automaticNow=(notes.automaticMask & (liveNoteState==null?15:notes.newAudioEventMask(liveNoteState)))!=0;
                receiveScoringNotes(notes);
                if(ledPreview!=null){if(notes.online)ledPreview.setStrumNotes(notes.newAudioEventMask(liveNoteState),notes.midi);else ledPreview.clearStrumEvents();}
                liveNoteState=notes;
                if(!notes.online){currentNote="--";updateNoteLabels();}
                else if(!played.isEmpty()){currentNote=played;setStatusText((automaticNow?"自动播放 · ":"拨弦 · ")+played);updateNoteLabels();}
                else if(!fingers.isEmpty()&&!instrumentIntent&&!(deviceSongState!=null&&deviceSongState.state!=0&&deviceSongState.instrument)){currentNote=fingers;setStatusText(settings!=null&&!settings.showFingering()?"等待拨弦":"指法 · "+fingers+"（待拨弦）");updateNoteLabels();}
            });
            return;
        }
        if(u.equals(STATUS) && d.length==20 && (d[0]&255)==0xA5){
            TouchState value=TouchState.parse(d);
            if(value!=null)main.post(()->{if(origin!=null && origin==gatt)applyTouchState(value);});
            return;
        }
        if(u.equals(STATUS) && d.length==13 && (d[0]&255)==0xA4){
            GestureState value=GestureState.parse(d);
            if(value!=null)main.post(()->{if(origin!=null && origin==gatt)applyGestureState(value);});
            return;
        }
        if (u.equals(STATUS) && (d.length == 16 || d.length == 20) && (d[0] & 255) == 0xA0) {
            ControllerState state = ControllerState.parse(d);
            if (state != null) main.post(() -> {if(origin!=null && origin==gatt)applyControllerState(state);});
            return;
        }
        if (u.equals(SPECTRUM) && d.length >= 20 && d.length <= 240 && d.length%20==0 && (d[0]&255)>=0xa1 && (d[0]&255)<=0xa4) {
            final byte[] fragment=d.clone();final int epoch=analysisEpoch;
            if(audioWorker.isShutdown())return;
            try{audioWorker.execute(()->{
                if(origin==null || origin!=gatt || epoch!=analysisEpoch)return;
                if(parserEpoch!=epoch){analysisParser.reset();parserEpoch=epoch;}
                AudioAnalysisFrame completed=null;
                for(int i=0;i<fragment.length;i+=20){
                    System.arraycopy(fragment,i,analysisPacket,0,20);
                    if(analysisParser.accept(analysisPacket))completed=analysisParser.snapshot(SystemClock.uptimeMillis());
                }
                if(completed!=null)synchronized(audioFrameLock){
                    if(epoch!=analysisEpoch)return;
                    pendingAudioFrame=completed;pendingAudioOrigin=origin;pendingAudioEpoch=epoch;
                    // One pending UI image: never queue historical frames behind slow rendering.
                    if(!audioRenderPosted){audioRenderPosted=true;main.post(renderAudioFrame);}
                }
            });}catch(java.util.concurrent.RejectedExecutionException ignored){}
        }
        // Current ESP32 status is 12 bytes: timestamp[0..3], note[4],
        // reserved[5..7], chips[8], flags[9], scan[10..11]. Timestamp bytes
        // must never be presented as timbre or volume.
        else if (u.equals(STATUS) && d.length == 12) runOnUiThread(() -> { if (settingsSynced && (d[9]&1)==0) setStatusText("已连接 · FPGA 离线"); });
        else if (u.equals(EVENT) && d.length >= 5) {
            if(liveNoteState==null)runOnUiThread(()->{
                currentNote="待回读";setStatusText("需更新配套音符反馈固件");updateNoteLabels();
            });
        }
        else if (u.equals(SONG_STATUS) && d.length >= 2) {
            final byte[] reply=d.clone();main.post(()->{if(origin!=null && origin==gatt)receiveSongResult(reply);});
            if((d[1]&255)==0x16){
                final LedGuideFrame frame=LedGuideFrame.parse(d);
                if(frame!=null)main.post(()->{
                    if(origin!=null&&origin==gatt&&frame.follows(polyGuideFrame)){
                        polyGuideFrame=frame;guideCols=frame.mask();guideLevel=frame.level;guideRow=-1;
                        StringBuilder names=new StringBuilder();
                        for(int c=0;c<4;c++)if((guideCols&(1<<c))!=0){if(names.length()>0)names.append(" · ");names.append(ScorePitch.name(ScorePitch.OPEN[c]+frame.frets[c]+activeCapo()));}
                        guideNote=names.length()>0?names.toString():"--";
                        if(guideMetric!=null)guideMetric.setText(guideCols==0?"等待下一拍":Integer.bitCount(guideCols)+" 音指引");
                        if(ledPreview!=null)ledPreview.setGuideLevels(frame.frets,frame.levels);
                        if(songGuideView!=null&&hardwarePlayingId!=null&&hardwarePlayingId.equals(selectedSongId))songGuideView.setDeviceFrets(frame.frets,frame.levels);
                        updateNoteLabels();
                    }
                });
            }
            if (d[1] == 0x15 && d.length >= 6) {
                guideCols = d[2] & 15; guideRow = d[3] & 255; guideLevel = d[4] & 255;
                guideNote = noteForMask(guideCols, guideRow);
                if(songGuideView!=null && hardwarePlayingId!=null && hardwarePlayingId.equals(selectedSongId))
                    main.post(()->songGuideView.setDeviceEvent(guideCols,guideRow));
                runOnUiThread(() -> { if (guideMetric != null) guideMetric.setText("第 " + (guideRow + 1) + " 行"); updateNoteLabels(); if (ledPreview != null) ledPreview.setGuide(guideCols, guideRow, guideLevel); });
            }
            if (d[1] == 0x15 && d.length >= 6 && (d[2] & 15) == 0) { guideCols = 0; guideRow = -1; guideLevel = 0; guideNote = "--"; runOnUiThread(() -> { updateNoteLabels(); if (ledPreview != null) ledPreview.setGuide(0,0,0); }); }
            // Device result handler owns completion/failure text; guide events only move the display.
        }
    }

    private void settleAudioMtu(BluetoothGatt g,int mtu){
        if(g!=gatt)return;
        negotiatedMtu=Math.max(23,mtu);
        if(mtuSettled)return;
        mtuSettled=true;setStatusText("订阅数据…");armBleTimeout(g,"订阅数据");sendNextNotification();
    }
    private void refreshVisualizer() {
        if(visualizer!=null && (!powerAllowsControls() || !analysisFresh))visualizer.clear();
    }

    /**
     * 把设备分析帧里的峰值 bin 交给调音表。
     *
     * <p>直接用 peakBin 换算的频率分辨率约 11.9 Hz（A4 附近 ±23 音分），拿来调音太粗；
     * 这里在 TunerMath 里对峰值 bin 与左右相邻 bin 做抛物线插值，把分辨率提到亚 bin 级。
     * 数据过期或设备休眠时传 fresh=false，让指针回到闲置呼吸动画。
     */
    private void updateTunerFromAnalysis(boolean fresh) {
        if (tunerGauge == null) return;
        boolean usable = fresh && analysisFresh && powerAllowsControls();
        if (!usable) {
            tunerWasFresh = false;
            tunerGauge.setFrequency(0, false);
            return;
        }
        double hz = TunerMath.interpolatedHz(audioAnalysis.bins, audioAnalysis.peakBin, audioAnalysis.sampleRate, 4096);
        // 静音时固件仍可能上报一个非零 peakBin，而 interpolatedHz 的平坦谱回退会得到一个
        // “看起来像音高”的 bin 中心频率；这里用峰值 bin 的 dB 值（0 ≈ -96 dBFS）判定无信号，
        // 避免调音表对着空气显示一个固定频率。
        int peakBin = audioAnalysis.peakBin;
        boolean silent = peakBin <= 0 || peakBin >= audioAnalysis.bins.length || audioAnalysis.bins[peakBin] <= 2;
        tunerWasFresh = !silent && hz > 0;
        tunerGauge.setFrequency(tunerWasFresh ? hz : 0, tunerWasFresh);
    }

    private void requestAnalysisMode(){
        if(!bleReady || !powerAllowsControls())return;
        int mode=!activityVisible?3:currentPage==PAGE_FUN?0:currentPage==0?audioDisplayMode:3;
        if(selectedAnalysisSupported)writeCommand("ANALYSIS3:"+mode);
        else if(mode!=3)writeCommand("ANALYSIS2:"+mode);
    }
    private void addAudioControls(LinearLayout parent){
        LinearLayout modes=row();String[] names={"频谱","波形","李萨如"};
        for(int i=0;i<3;i++){final int value=i;TextView button=outlineAction(names[i]);button.setOnClickListener(v->{audioDisplayMode=value;requestAnalysisMode();if(visualizer!=null)visualizer.setMode(value);});if(i>0)addSpace(modes,8);modes.addView(button,new LinearLayout.LayoutParams(0,-2,1));}
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);parent.addView(modes,lp);
        LinearLayout extra=row();TextView range=outlineAction("频段 / 幅度"),full=outlineAction("全屏查看");
        range.setOnClickListener(v->{if(visualizer!=null){if(audioDisplayMode==0)visualizer.toggleBand();else visualizer.toggleScale();}});
        full.setOnClickListener(v->openAudioFullScreen());extra.addView(range,new LinearLayout.LayoutParams(0,-2,1));addSpace(extra,8);extra.addView(full,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams elp=new LinearLayout.LayoutParams(-1,-2);elp.topMargin=dp(8);parent.addView(extra,elp);
    }
    private void openAudioFullScreen(){
        if(visualizer==null || fullScreenDialog!=null)return;
        final AudioScopeView scope=visualizer;final ViewGroup parent=(ViewGroup)scope.getParent();final int index=parent.indexOfChild(scope);final ViewGroup.LayoutParams old=scope.getLayoutParams();parent.removeView(scope);
        android.app.Dialog dialog=new android.app.Dialog(this,android.R.style.Theme_Material_NoActionBar);fullScreenDialog=dialog;
        LinearLayout content=column();content.setPadding(dp(16),dp(10),dp(16),dp(10));content.setBackgroundColor(cSurface());content.addView(scope,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout toolbar=row();String[] labels={"频谱","波形","李萨如","频段/幅度","退出"};
        for(int i=0;i<5;i++){final int action=i;TextView button=outlineAction(labels[i]);button.setOnClickListener(v->{if(action<3){audioDisplayMode=action;requestAnalysisMode();scope.setMode(action);}else if(action==3){if(audioDisplayMode==0)scope.toggleBand();else scope.toggleScale();}else dialog.dismiss();});if(i>0)addSpace(toolbar,8);toolbar.addView(button,new LinearLayout.LayoutParams(0,-2,1));}
        LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(-1,-2);tlp.topMargin=dp(8);content.addView(toolbar,tlp);
        dialog.setContentView(content);dialog.setOnDismissListener(d->{((ViewGroup)scope.getParent()).removeView(scope);parent.addView(scope,Math.min(index,parent.getChildCount()),old);fullScreenDialog=null;setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});
        dialog.show();dialog.getWindow().setLayout(-1,-1);dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN|android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        content.setClipToPadding(true);content.setClipChildren(true);
        content.setOnApplyWindowInsetsListener((v,insets)->{
            int l=insets.getSystemWindowInsetLeft(),r=insets.getSystemWindowInsetRight(),top=insets.getSystemWindowInsetTop(),b=insets.getSystemWindowInsetBottom();
            if(Build.VERSION.SDK_INT>=28 && insets.getDisplayCutout()!=null){android.view.DisplayCutout cutout=insets.getDisplayCutout();l=Math.max(l,cutout.getSafeInsetLeft());r=Math.max(r,cutout.getSafeInsetRight());top=Math.max(top,cutout.getSafeInsetTop());b=Math.max(b,cutout.getSafeInsetBottom());}
            v.setPadding(dp(16)+l,dp(10)+top,dp(16)+r,dp(10)+b);return insets;
        });content.requestApplyInsets();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void clearAudioTelemetry() {
        main.removeCallbacks(analysisExpired);analysisEpoch++;audioAnalysis=new AudioAnalysisFrame();analysisFresh=false;
        synchronized(audioFrameLock){pendingAudioFrame=null;}
        main.removeCallbacks(waveExpired); main.removeCallbacks(spectrumExpired);
        waveFresh = spectrumFresh = false;
        Arrays.fill(wave, 0); Arrays.fill(spectrum, 0); peakHz = sampleRate = 0;
        if (peakMetric != null) peakMetric.setText("等待数据");
        if (sampleMetric != null) sampleMetric.setText("-- Hz");
        refreshVisualizer();
    }

    private void updateNoteLabels() {
        String musical = styledNotes(currentNote);
        if (noteMetric != null) noteMetric.setText(musical);
        if(fullScreenDialog!=null&&!fullPractice&&fullNoteLabel!=null)fullNoteLabel.setText(hardwarePlayingId!=null?styledNotes(guideNote):musical);
        if (ledNoteLabel != null) ledNoteLabel.setText(hardwarePlayingId != null ? "曲谱指引  " + styledNotes(guideNote) : "演奏音符  " + musical);
    }

    /** 按「音符显示方式」把音名文本转换为唱名或品位数字。无法映射的音名原样保留。 */
    private String styledNotes(String text) {
        if (settings == null || text == null || text.isEmpty()) return text;
        int style = settings.noteStyle();
        if (style == SettingsStore.NOTE_STYLE_LETTER) return text;
        Matcher matcher = Pattern.compile("[A-G][#b]?-?\\d+").matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(text, last, matcher.start());
            int midi = noteMidi(matcher.group());
            out.append(midi < 0 ? matcher.group() : style == SettingsStore.NOTE_STYLE_SOLFEGE ? solfegeName(midi) : fretText(midi));
            last = matcher.end();
        }
        out.append(text, last, text.length());
        return out.toString();
    }

    private String solfegeName(int midi) {
        final String[] names = {"do", "do#", "re", "re#", "mi", "fa", "fa#", "sol", "sol#", "la", "la#", "si"};
        return names[(midi % 12 + 12) % 12];
    }

    /** 品位数字：优先选最低品位把位；超出 0..17 品时退回音名。 */
    private String fretText(int midi) {
        int column = noteColumnForMidi(midi);
        if (column < 0) return noteName(midi);
        int fret = midi - new int[]{67, 60, 64, 69}[column];
        return fret == 0 ? "空弦" : fret + "品";
    }

    // 物理列顺序与屏幕/FPGA约定一致：G、C、E、A；row 0 是空弦，row 1..17 是品位。
    private String noteForMask(int mask, int fret) {
        if (mask == 0 || fret < 0 || fret > 18) return "--";
        StringBuilder out = new StringBuilder(); int[] open = {67, 60, 64, 69};
        for (int col = 0; col < 4; ++col) if ((mask & (1 << col)) != 0) {
            if (out.length() > 0) out.append(" / "); out.append(noteName(open[col] + fret));
        }
        return out.length() == 0 ? "--" : out.toString();
    }

    private String noteName(int midi) {
        final String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int octave = midi / 12 - 1; return names[(midi % 12 + 12) % 12] + octave;
    }

    private int stringIndex(String token) {
        String s = token.trim().toUpperCase();
        if (s.equals("G") || s.equals("0")) return 0; if (s.equals("C") || s.equals("1")) return 1;
        if (s.equals("E") || s.equals("2")) return 2; if (s.equals("A") || s.equals("3")) return 3; return -1;
    }

    private int noteMidi(String token) {
        Matcher m = Pattern.compile("^([A-Ga-g])([#b♯♭]?)(-?\\d+)$").matcher(token.trim()); if (!m.matches()) return -1;
        String n = m.group(1).toUpperCase(); int pc = "CDEFGAB".indexOf(n); int[] base = {0, 2, 4, 5, 7, 9, 11};
        pc = base[pc]; String accidental = m.group(2); if (accidental.equals("#") || accidental.equals("♯")) pc++; if (accidental.equals("b") || accidental.equals("♭")) pc--; return (Integer.parseInt(m.group(3)) + 1) * 12 + pc;
    }

    private int noteColumnForMidi(int midi) {
        int[] open = {67, 60, 64, 69}; int best = -1; int bestFret = 99;
        for (int i = 0; i < open.length; ++i) { int fret = midi - open[i]; if (fret >= 0 && fret <= 18 && fret < bestFret) { best = i; bestFret = fret; } }
        return best;
    }

    private boolean powerAllowsControls(){return powerState==null || powerState.playable();}
    private View buildDashboardBattery(){
        LinearLayout card=card();
        LinearLayout summary=row();
        LinearLayout details=column();
        details.addView(cardLabel("乐器电量"));
        batteryStatusDash=kit.text("",ts(12),cTextSecondary());
        details.addView(batteryStatusDash);
        summary.addView(details,new LinearLayout.LayoutParams(0,-2,1));
        batteryGaugeDash=new BatteryGaugeView(this);
        summary.addView(batteryGaugeDash,new LinearLayout.LayoutParams(dp(38),dp(22)));
        batteryPercentDash=kit.textBold("—",ts(23),cTextPrimary());
        LinearLayout.LayoutParams percentLp=new LinearLayout.LayoutParams(-2,-2);
        percentLp.leftMargin=dp(8);
        summary.addView(batteryPercentDash,percentLp);
        card.addView(summary);
        batteryBarDash=kit.linearProgress(cAccent());
        batteryBarDash.setMax(100);
        batteryBarDash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams barLp=new LinearLayout.LayoutParams(-1,dp(6));
        barLp.topMargin=dp(10);
        card.addView(batteryBarDash,barLp);
        updateBatteryUi();
        return card;
    }

    private View buildPowerCard(boolean dashboard){
        LinearLayout card=card();
        card.addView(cardLabel("设备工作状态"));
        TextView status=kit.text("等待电源状态回读",ts(14),cTextSecondary());
        status.setPadding(0,dp(8),0,dp(10));card.addView(status);
        if(!dashboard){
            batterySettings=kit.text("电量与充电 · 等待设备回读",ts(13),cTextSecondary());
            batterySettings.setPadding(0,0,0,dp(8));card.addView(batterySettings);
        }
        TextView action=primaryAction("连接并唤醒");card.addView(action);
        action.setOnClickListener(v->{tap();requestPower(false);});
        if(dashboard){powerStatusDash=status;powerActionDash=action;}
        else{
            powerStatusSettings=status;powerActionSettings=action;
            infraredSettings=kit.text("",ts(12),cTextSecondary());
            infraredSettings.setPadding(0,dp(8),0,0);card.addView(infraredSettings);
            powerSleepButton=outlineAction("立即休眠并熄屏");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);card.addView(powerSleepButton,lp);
            powerSleepButton.setOnClickListener(v->{tap();
                if(hasDeviceSongIntent() || guideRunning)AppDialogs.message(this,"进入休眠？","本次曲谱播放将停止，屏幕熄灭后可轻触或通过 App 唤醒。","休眠",new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int w){stopDeviceSong();requestPower(true);}},"继续练习",null,null,null);
                else requestPower(true);
            });
            powerAutoValue=kit.text("自动休眠：等待设备设置",ts(12),cTextSecondary());powerAutoValue.setPadding(0,dp(12),0,dp(6));card.addView(powerAutoValue);
            int[] minutes={3,5,10,15,30,0};sleepMinuteButtons=new TextView[6];
            for(int rowIndex=0;rowIndex<2;rowIndex++){
                LinearLayout line=row();
                for(int j=0;j<3;j++){
                    final int idx=rowIndex*3+j,m=minutes[idx];TextView b=outlineAction(m==0?"关闭":m+" 分钟");sleepMinuteButtons[idx]=b;
                    LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1);bp.setMargins(dp(2),dp(3),dp(2),0);line.addView(b,bp);
                    b.setOnClickListener(v->{tap();int ticket=++powerTicket&65535;if(writeCommand("POWERCFG:"+m+","+ticket))powerAutoValue.setText("正在保存自动休眠设置…");});
                }card.addView(line);
            }
        }
        updateBatteryUi();
        return card;
    }
    private void updateBatteryUi(){
        String text=!bleReady?"电量与充电 · 设备未连接":
                batteryState==null?"电量与充电 · 暂无回读":batteryState.label();
        if(batteryGaugeDash!=null){
            BatteryState current=bleReady?batteryState:null;
            int level=current!=null&&current.hasLevel()?current.percent:-1;
            batteryGaugeDash.setState(level,current!=null&&current.isCharging());
            batteryPercentDash.setText(current==null?"—":current.levelLabel());
            batteryBarDash.setMax(4);
            batteryBarDash.setProgress(current==null?0:Math.max(0,current.bars()));
            batteryStatusDash.setText(!bleReady?"设备未连接":current==null?"等待电量回读":
                    level<0?"电量未读到 · "+current.chargeLabel():current.chargeLabel());
        }
        if(batterySettings!=null)batterySettings.setText(text);
        if(infraredSettings!=null){
            String[] states={"","红外发射 · 已关闭","红外发射 · 已开启","红外发射 · 回读失败，请检查发射板连接"};
            String state=bleReady&&batteryState!=null?states[batteryState.infrared]:"";
            infraredSettings.setText(state);
            infraredSettings.setVisibility(state.isEmpty()?View.GONE:View.VISIBLE);
        }
    }
    private void requestPower(boolean sleep){
        if(!bleReady){
            if(sleep){toast("请先连接设备");return;}
            scan(false);pendingWakeAfterConnect=true;updatePowerUi();return;
        }
        if(powerState==null || !powerState.supported()){
            pendingWakeAfterConnect=!sleep;writeCommand("POWER:QUERY");
            toast("正在确认休眠能力，请确保 FPGA/ESP32 为配套新版");return;
        }
        pendingWakeAfterConnect=false;
        if(sleep){cancelPendingSongPlay();stopSongGuide("设备准备休眠");}
        int ticket=++powerTicket&65535;
        if(writeCommand("POWER:"+(sleep?"SLEEP":"WAKE")+","+ticket)){
            String text=sleep?"正在请求休眠…":"正在请求点亮屏幕并唤醒…";
            if(powerStatusDash!=null)powerStatusDash.setText(text);if(powerStatusSettings!=null)powerStatusSettings.setText(text);
            if(powerTimeout!=null)main.removeCallbacks(powerTimeout);
            powerTimeout=()->{if(powerState==null || powerState.origin!=2 || powerState.request!=ticket || powerState.result==1){
                if(powerStatusDash!=null)powerStatusDash.setText("尚未确认完成，请检查连接并重试");
                if(powerStatusSettings!=null)powerStatusSettings.setText("尚未确认完成，请检查连接并重试");
            }};main.postDelayed(powerTimeout,6500);
        }
    }
    private void applyPowerState(PowerState value){
        if(!value.newerThan(powerState))return;
        boolean wasPlayable=powerAllowsControls();
        powerState=value;
        if(value.origin==2 && value.request==(powerTicket&65535) && value.result!=1 && powerTimeout!=null)main.removeCallbacks(powerTimeout);
        if(!value.playable()){
            if(performanceTake!=null){performanceTake.stop();updateTakeUi();}
            clearAudioTelemetry();currentNote="--";liveNoteState=null;updateNoteLabels();cancelPendingSongPlay();
            if(guideRunning)stopSongGuide("设备进入待机/恢复，唤醒后可重新预览");
            if(ledPreview!=null){polyGuideFrame=null;ledPreview.clearGuide();ledPreview.clearStrumEvents();}
        }
        setControlsEnabled(bleReady);updatePowerUi();
        if(!wasPlayable && value.playable())requestAnalysisMode();
        if(pendingWakeAfterConnect && value.supported()){pendingWakeAfterConnect=false;requestPower(false);}
    }
    private void updatePowerUi(){
        updateBatteryUi();
        String text=!bleReady?"设备未连接":powerState==null?"等待设备电源状态回读":powerState.label();
        if(powerStatusDash!=null)powerStatusDash.setText(text);if(powerStatusSettings!=null)powerStatusSettings.setText(text);
        boolean supported=powerState!=null && powerState.supported();
        boolean transition=supported && (powerState.state==3 || powerState.state==5);
        for(TextView b:new TextView[]{powerActionDash,powerActionSettings})if(b!=null){
            b.setText(!bleReady?"连接并唤醒":!supported?"查询休眠能力":powerState.state<=2?(powerState.state==2?"继续使用":"屏幕已亮 · 正常工作"):transition?"正在恢复状态…":"一键唤醒并点亮屏幕");
            b.setEnabled(!transition && (!bleReady || !supported || powerState.state>=4 || powerState.state==2));
        }
        if(powerSleepButton!=null)powerSleepButton.setEnabled(bleReady && supported && powerState.state!=3 && powerState.state!=4 && powerState.state!=5);
        if(powerAutoValue!=null)powerAutoValue.setText(!supported?"自动休眠：等待配套固件回读":powerState.minutes==0?"自动休眠：已关闭":"自动休眠："+powerState.minutes+" 分钟，提前 10 秒提醒");
        int[] values={3,5,10,15,30,0};if(sleepMinuteButtons!=null)for(int i=0;i<6;i++){
            sleepMinuteButtons[i].setEnabled(bleReady && supported);setChip(sleepMinuteButtons[i],supported && powerState.minutes==values[i]);
        }
        updateModelUi();updateChainUi();updateCapoUi();updateGuideFadeUi();
        if(ledPreview!=null)ledPreview.setSleeping(!powerAllowsControls());
        if(visualizer!=null)visualizer.setStandbyLabel(powerState!=null && !powerState.playable()?powerState.label():"等待数据");
    }

    private void updateGuideFadeUi(){updatingFade=true;for(android.widget.Switch toggle:fadeToggles){toggle.setChecked(guideSoft);toggle.setEnabled(guideSoftKnown&&bleReady&&powerAllowsControls());}updatingFade=false;}

    private boolean writeCommand(String s) {
        if(!powerAllowsControls() && !s.startsWith("POWER") && !s.startsWith("PRACTICE:") &&
           !s.equals("STATE") && !s.equals("LM_GET") && !s.equals("CAPO_GET") && !s.equals("MODEL_GET") && !s.equals("XC_GET") && !s.equals("GUIDE_FADE_GET") && !s.equals("FX_GET") && !s.equals("TOUCH_GET") && !s.equals("GESTURE_GET") && !s.equals("GESTURE_GET2") && !s.equals("SONG_STOP") && !s.equals("SONG_INFO")){
            toast("设备正在休眠或恢复，请先唤醒");return false;
        }
        if (gatt == null || commandChar == null || !bleReady) { toast("请先完成 ESP32 连接"); return false; }
        if (!ControlAvailability.canSend(bleReady,controllerState==null?0:controllerState.version,s)) {
            toast("当前 ESP32 固件不支持演奏模式切换，请更新固件");return false;
        }
        boolean queued=s.startsWith("POWER:") || s.startsWith("POWERCFG:") ?
            commandQueue.offerPower(s,songWriteBusy && activeWrite==commandChar):
            commandQueue.offer(s,songWriteBusy && activeWrite==commandChar);
        if (!queued) { toast("指令发送中，请稍候"); return false; }
        sendNextSongPacket();
        return true;
    }

    private void addVibratoThreshold(LinearLayout card) {
        card.addView(new View(this), new LinearLayout.LayoutParams(-1,dp(12)));
        touchThresholdLabel=cardLabel("颤音触发门限");
        card.addView(touchThresholdLabel);
        card.addView(hint("两种判定共用 · 120–2400 · 数值越高越难触发"));
        touchThresholdBar=kit.seekBar(cAccent());
        touchThresholdBar.setMax(114);
        card.addView(touchThresholdBar);
        touchThresholdBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser) {
                if(fromUser){
                    touchThresholdDraft=120+value*20;
                    touchThresholdDirty=true;
                    updateTouchUi();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        touchThresholdApply=outlineAction("保存门限");
        addPracticeButtonRow(card,8,touchThresholdApply);
        touchThresholdApply.setOnClickListener(v->requestTouchChange(TouchEdit.THRESHOLD));
        touchThresholdStatus=kit.text("连接设备后读取实际门限",ts(12),cTextSecondary());
        touchThresholdStatus.setPadding(0,dp(8),0,0);
        card.addView(touchThresholdStatus);
        updateTouchUi();
    }

    private void addTouchControls(LinearLayout body){
        body.addView(sectionTitle("参数调整"));
        LinearLayout box=card();
        box.addView(cardLabel("电容转换档位"));LinearLayout rate=row();
        touchFast128=segment("128 · 稳定",true);touchFast64=segment("64 · 快速",false);
        rate.addView(touchFast128,new LinearLayout.LayoutParams(0,-2,1));addSpace(rate,10);rate.addView(touchFast64,new LinearLayout.LayoutParams(0,-2,1));box.addView(rate);
        touchFast128.setOnClickListener(v->{touchFastDraft=0;touchTimingDirty=true;updateTouchUi();});
        touchFast64.setOnClickListener(v->{touchFastDraft=1;touchTimingDirty=true;updateTouchUi();});
        touchGraceSwitch=kit.toggle(false);box.addView(settingsRow("同拨容错","发现换品时等待确认，稳定按品立即发声",touchGraceSwitch));
        touchGraceLabel=kit.text("关闭 · 立即取音",ts(13),cTextSecondary());box.addView(touchGraceLabel);
        touchGraceBar=kit.seekBar(cAccent());touchGraceBar.setMax(60);box.addView(touchGraceBar);
        touchGraceSwitch.setOnCheckedChangeListener((v,on)->{if(!updatingTouchUi){touchGraceDraft=on?6:0;touchTimingDirty=true;updateTouchUi();}});
        touchGraceBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int n,boolean user){if(user){touchGraceDraft=n;touchTimingDirty=true;updateTouchUi();}}
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        });
        LinearLayout actions=row();touchQuick=segment("快速同拨",false);TextView quick=touchQuick;touchApply=segment("应用并保存",false);
        actions.addView(quick,new LinearLayout.LayoutParams(0,-2,1));addSpace(actions,10);actions.addView(touchApply,new LinearLayout.LayoutParams(0,-2,1));box.addView(actions);
        quick.setOnClickListener(v->{touchFastDraft=1;touchGraceDraft=6;touchTimingDirty=true;updateTouchUi();});
        touchApply.setOnClickListener(v->requestTouchChange(TouchEdit.TIMING));
        touchStatus=kit.text("连接设备后读取实际触摸设置",ts(12),cTextSecondary());touchStatus.setPadding(0,dp(10),0,0);box.addView(touchStatus);
        TextView help=segment("了解触摸与同拨设置",false);box.addView(help);
        help.setOnClickListener(v->AppDialogs.message(this,"触摸与同拨", "DECIM 64 转换更快，但更容易受到电容噪声影响；128 更稳。切换时请暂停演奏，系统保留音色和正在衰减的尾音。\n\n配套新版 FPGA 只在扫描发现尚未确认的换品时等待；稳定按品立即发声。新品确认后提前发声，到最长等待时间仍未确认则使用已确认品位。建议从 64＋6 ms 试起，等待越长，换品时越可能感觉迟缓。\n\n如果手指刚按下但扫描还没读到，系统暂时没有换品证据；短等待不能保证完全同时按品、拨弦都选中新音。两轮确认和最高品规则始终保留。旧保存值仍保留为等待上限，需同时更新 FPGA 与 App。\n\n这是独立于一拍锁定／四弦复音的设置，保存于乐器内。", "知道了",null,null,null,null,null));
        addContent(body,box);updateTouchUi();
    }
    private boolean touchControlsReady(){
        return bleReady && powerAllowsControls() && touchState!=null && touchState.fresh()
                && !touchExpired && pendingTouchEdit==null && !touchState.waiting();
    }

    private void updateTouchUi(){
        if(touchState!=null && pendingTouchEdit==null){
            if(!touchThresholdDirty)touchThresholdDraft=touchState.threshold;
            if(!touchTimingDirty){
                touchFastDraft=touchState.fast;
                touchGraceDraft=touchState.grace;
            }
        }
        boolean ready=touchControlsReady();
        String unavailable=!bleReady?"连接设备后读取实际设置"
            :pendingTouchEdit!=null?"等待 FPGA 与电容芯片确认"
            :touchExpired?"触摸状态已过期，等待重新读取"
            :touchState==null?"尚未收到触摸设置，请更新配套固件":null;
        if(touchThresholdBar!=null){
            touchThresholdBar.setProgress((touchThresholdDraft-120)/20);
            touchThresholdLabel.setText("颤音触发门限 · "+touchThresholdDraft);
            touchThresholdBar.setContentDescription("颤音触发门限 "+touchThresholdDraft+"，可调范围 120 到 2400");
            touchThresholdBar.setEnabled(ready);
            touchThresholdApply.setEnabled(ready && touchThresholdDirty);
            touchThresholdStatus.setText(unavailable!=null?unavailable:
                touchState.thresholdStatus()+(touchThresholdDirty?" · 有未保存的调整":""));
        }
        if(touchStatus!=null){
            updatingTouchUi=true;
            touchGraceBar.setProgress(touchGraceDraft);
            touchGraceLabel.setText(touchGraceDraft==0?"关闭 · 立即取音":"换品时最长等待 · "+touchGraceDraft+" ms");
            touchGraceSwitch.setChecked(touchGraceDraft!=0);
            setSegment(touchFast128,touchFastDraft==0);
            setSegment(touchFast64,touchFastDraft==1);
            touchGraceBar.setEnabled(ready && touchGraceDraft!=0);
            touchGraceSwitch.setEnabled(ready);
            touchQuick.setEnabled(ready);
            touchFast128.setEnabled(ready);
            touchFast64.setEnabled(ready);
            touchApply.setEnabled(ready && touchTimingDirty);
            touchStatus.setText(unavailable!=null?unavailable:
                touchState.status()+(touchTimingDirty?" · 有未应用的调整":""));
            updatingTouchUi=false;
        }
    }

    private void requestTouchChange(TouchEdit edit){
        if(!touchControlsReady())return;
        boolean thresholdOnly=edit==TouchEdit.THRESHOLD;
        if(thresholdOnly?!touchThresholdDirty:!touchTimingDirty)return;
        // The firmware expects a complete settings packet. Use confirmed
        // values for the other card so its unsaved edits stay in the app.
        int threshold=thresholdOnly?touchThresholdDraft:touchState.actualThreshold;
        int fast=thresholdOnly?touchState.actualFast:touchFastDraft;
        int grace=thresholdOnly?touchState.actualGrace:touchGraceDraft;
        touchTicket=touchTicket%65535+1;
        if(!writeCommand("TC:"+threshold+","+fast+","+grace+","+touchTicket))return;
        pendingTouchEdit=edit;
        updateTouchUi();
        if(touchTimeout!=null)main.removeCallbacks(touchTimeout);
        final int expected=touchTicket;
        touchTimeout=()->{
            if(pendingTouchEdit==null || expected!=touchTicket)return;
            pendingTouchEdit=null;
            updateTouchUi();
            TextView status=thresholdOnly?touchThresholdStatus:touchStatus;
            if(status!=null)status.setText("设置确认超时，请检查连接后重试");
        };
        main.postDelayed(touchTimeout,5500);
    }

    private void applyTouchState(TouchState value){
        if(!value.acceptsAfter(touchState))return;
        touchState=value;
        touchExpired=false;
        main.removeCallbacks(touchStateExpired);
        main.postDelayed(touchStateExpired,2000);
        if(pendingTouchEdit!=null && value.ticket==touchTicket && value.result!=0 && value.result!=1){
            if(value.result==2){
                if(pendingTouchEdit==TouchEdit.THRESHOLD)touchThresholdDirty=false;
                else touchTimingDirty=false;
            }
            pendingTouchEdit=null;
            if(touchTimeout!=null)main.removeCallbacks(touchTimeout);
        }
        updateTouchUi();
    }
    private void applyFxState(FxState value){
        if(!value.acceptsAfter(fxState))return;
        fxState=value;fxExpired=false;
        main.removeCallbacks(fxStateExpired);main.postDelayed(fxStateExpired,2000);
        updateChainUi();
    }

    private static final String[] CHAIN_NAMES={"震音","双声合唱","喷气弗兰格","电吉他失真"};
    private static final String[] CHAIN_ROUTES={"所有音符","仅下一个音","仅 G 弦","仅 C 弦","仅 E 弦","仅 A 弦"};
    private boolean chainReady(){return bleReady&&powerAllowsControls()&&!chainExpired&&chainState!=null&&chainState.fresh();}

    private void addChainControls(LinearLayout parent){
        parent.addView(sectionTitle("效果器"));
        LinearLayout box=card();
        chainRoute=outlineAction("作用于："+CHAIN_ROUTES[chainTarget]);
        chainRoute.setOnClickListener(v->{
            java.util.ArrayList<AppDialogs.Item> options=new ArrayList<>();
            for(int i=0;i<CHAIN_ROUTES.length;i++)options.add(new AppDialogs.Item(CHAIN_ROUTES[i],
                i==0?"每个音符都加效果":i==1?"只处理接下来弹响的一个音，之后恢复原声":"只给这根弦加效果",false,i==chainTarget?"当前":null));
            AppDialogs.list(this,"给哪些音符加效果？",options,n->{chainTarget=n;sendChain();},"取消",null,null,null);
        });
        box.addView(chainRoute);
        for(int i=0;i<4;i++){
            final int index=i;
            LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.CheckBox check=new android.widget.CheckBox(this);
            check.setText(CHAIN_NAMES[i]);check.setTextColor(cTextPrimary());check.setMinHeight(dp(48));
            check.setButtonTintList(android.content.res.ColorStateList.valueOf(cAccent()));
            head.addView(check,new LinearLayout.LayoutParams(0,-2,1));chainChecks[i]=check;
            chainValues[i]=kit.text("",ts(13),cTextSecondary());head.addView(chainValues[i]);box.addView(head);
            SeekBar bar=kit.seekBar(cAccent());chainBars[i]=bar;bar.setMax(FxState.maxAmount(i+1));
            bar.setContentDescription(CHAIN_NAMES[i]+"强度");box.addView(bar,new LinearLayout.LayoutParams(-1,dp(42)));
            check.setOnCheckedChangeListener((v,on)->{
                if(chainUpdating)return;
                if(on){chainMask|=1<<index;if(chainAmounts[index]==0)chainAmounts[index]=bar.getMax()/2;}
                else chainMask&=~(1<<index);
                sendChain();
            });
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onStartTrackingTouch(SeekBar b){chainDragging[index]=true;}
                public void onStopTrackingTouch(SeekBar b){chainDragging[index]=false;sendChain();}
                public void onProgressChanged(SeekBar b,int n,boolean user){
                    chainValues[index].setText(Math.round(n*100f/b.getMax())+"%");
                    if(user){chainAmounts[index]=n;if(!chainDragging[index])sendChain();}
                }
            });
        }
        chainClear=outlineAction("关闭全部效果");chainClear.setOnClickListener(v->{chainMask=0;sendChain();});box.addView(chainClear);
        chainStatus=kit.text("连接乐器后可调节",ts(12),cTextSecondary());chainStatus.setPadding(0,dp(10),0,0);
        box.addView(chainStatus);addContent(parent,box);updateChainUi();
    }

    private void sendChain(){
        if(!chainReady()){if(!chainPending)syncChainControls();updateChainUi();return;}
        int ticket=chainTicket%32767+1;
        if(!writeCommand(ChainState.command(chainMask,chainAmounts,chainTarget,ticket))){
            if(!chainPending)syncChainControls();updateChainUi();return;
        }
        chainTicket=ticket;chainPending=true;chainFeedback=null;updateChainUi();
        main.postDelayed(()->{if(chainPending&&chainTicket==ticket){
            chainPending=false;chainFeedback="效果切换超时，请重试";updateChainUi();writeCommand("XC_GET");
        }},6000);
    }

    private void receiveChain(ChainState state){
        if(!state.acceptsAfter(chainState))return;
        chainState=state;chainExpired=false;main.removeCallbacks(chainExpiry);main.postDelayed(chainExpiry,2000);
        if(chainPending&&state.ticket==chainTicket&&state.result>=2){
            chainPending=false;
            chainFeedback=state.result==2?null:state.result==6?"效果已生效，设备保存失败":"效果未确认，请重试";
        }
        updateChainUi();
    }

    private void syncChainControls(){
        if(chainState==null||chainExpired||!chainState.fresh())return;
        chainMask=chainState.enabled?chainState.mask:0;
        chainTarget=chainState.enabled?chainState.target:0;
        for(int i=0;i<4;i++)if(!chainDragging[i])chainAmounts[i]=chainState.amount[i];
        if(!chainState.enabled&&fxState!=null&&!fxExpired&&fxState.fresh()&&fxState.actual>=1&&fxState.actual<=4){
            int index=fxState.actual-1;
            chainMask=1<<index;
            if(!chainDragging[index])chainAmounts[index]=fxState.gain;
        }
    }

    private void updateChainUi(){
        if(chainStatus==null)return;
        if(!chainPending)syncChainControls();
        boolean ready=chainReady();chainUpdating=true;
        for(int i=0;i<4;i++){
            boolean on=(chainMask&(1<<i))!=0;
            chainChecks[i].setChecked(on);chainChecks[i].setEnabled(ready);
            chainBars[i].setEnabled(ready&&on);
            if(!chainDragging[i])chainBars[i].setProgress(chainAmounts[i]);
            chainValues[i].setText(Math.round(chainAmounts[i]*100f/chainBars[i].getMax())+"%");
        }
        chainUpdating=false;
        chainClear.setEnabled(ready&&chainMask!=0);chainRoute.setEnabled(ready);
        chainRoute.setText("作用于："+CHAIN_ROUTES[chainTarget]);
        chainStatus.setText(!bleReady?"连接乐器后可调节":!ready?"等待乐器效果状态":chainPending?"正在切换效果…":
            chainFeedback!=null?chainFeedback:chainMask==0?"原声":!chainState.enabled?"当前效果已载入":
            chainTarget==1?(chainState.armed==1?"等待下一个音":"本次已结束，点选「仅下一个音」可再次开启"):
            (chainState.flags&4)==0?"效果正在平滑切换":"效果已开启");
    }

    private static final String[] MODEL_FEEL={"自然木质","温暖拨弦","清脆簧片","泛音长尾"};
    private boolean modernModelNames(){
        return modelState==null||!modelState.fresh()||modelState.caps==7;
    }
    private void addModelControls(LinearLayout box){
        modelChoices.clear();
        for(int r=0;r<2;r++){
            LinearLayout line=row();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(10);
            for(int c=0;c<2;c++){
                final int index=r*2+c;if(c>0)addSpace(line,10);
                TextView b=outlineAction(ModelState.name(index,true)+"\n"+MODEL_FEEL[index]);
                b.setSingleLine(false);b.setMaxLines(3);b.setGravity(Gravity.CENTER);b.setMinHeight(dp(72));
                line.addView(b,new LinearLayout.LayoutParams(0,-2,1));modelChoices.add(b);
                b.setOnClickListener(v->{tap();requestModel(index);});
            }
            box.addView(line,lp);
        }
        modelStatus=kit.text("连接乐器后可切换",ts(12),cTextSecondary());
        modelStatus.setPadding(0,dp(10),0,0);box.addView(modelStatus);updateModelUi();
        envelopeButton=outlineAction("塑造音色 · ADSR");
        envelopeButton.setOnClickListener(v->{tap();openEnvelope();});
        addPracticeButtonRow(box,8,envelopeButton);
        updateEnvelopeUi();
    }

    private boolean envelopeReady(){
        return bleReady&&powerAllowsControls()&&envelopeState!=null&&envelopeState.fresh
                &&SystemClock.uptimeMillis()-envelopeReceived<2200;
    }

    private static int envelopeAddress(int bank,int midi){return bank*128+midi;}

    private void clearEnvelopeCache(){
        envelopeKnownBanks=0;
        java.util.Arrays.fill(envelopeKnownMask,0);
        java.util.Arrays.fill(envelopeModifiedMask,0);
    }

    private void receiveEnvelope(EnvelopeState state){
        envelopeState=state;envelopeReceived=SystemClock.uptimeMillis();
        if(state.fresh){
            if(envelopePending!=null&&envelopePending.op==3&&state.ticket==envelopeTicket&&state.result==2){
                clearEnvelopeCache();
            }
            envelopeKnownBanks|=1<<state.bank;
            envelopeKnownMask[state.bank]&=~(envelopeModifiedMask[state.bank]^state.modifiedMask);
            envelopeModifiedMask[state.bank]=state.modifiedMask;
            envelopeKnownMask[state.bank]|=EnvelopeState.bit(state.midi);
            envelopeValues[state.bank][state.midi-60]=state.value;
        }else{clearEnvelopeCache();envelopeEdits.clear();}
        if(envelopePending!=null&&state.ticket==envelopeTicket&&state.result!=1){
            envelopePending=null;
            if(state.fresh)flushEnvelope();
        }
        main.removeCallbacks(envelopeExpiry);main.postDelayed(envelopeExpiry,2200);
        updateEnvelopeUi();
        if(state.fresh)readEnvelopeNoteIfNeeded();
    }

    private boolean envelopeNoteKnown(){
        int bit=EnvelopeState.bit(envelopeMidi);
        return (envelopeKnownBanks&(1<<envelopeTimbre))!=0&&((envelopeModifiedMask[envelopeTimbre]&bit)==0||
                (envelopeKnownMask[envelopeTimbre]&bit)!=0);
    }

    private int envelopeDisplayed(){
        int key=envelopeAddress(envelopeTimbre,envelopeMidi);
        if(!envelopeReady() && envelopeDrafts.containsKey(key))return envelopeDrafts.get(key);
        EnvelopeEdit edit=envelopeEdits.get(key);
        if(edit==null&&envelopePending!=null&&envelopePending.bank==envelopeTimbre&&envelopePending.midi==envelopeMidi)edit=envelopePending;
        if(edit!=null&&edit.op!=0)return edit.value;
        if(envelopeEdits.containsKey(-1)||(envelopePending!=null&&(envelopePending.op==3||
                (envelopePending.op==2&&envelopePending.bank==envelopeTimbre))))return EnvelopeState.FACTORY;
        int bit=EnvelopeState.bit(envelopeMidi);
        if((envelopeModifiedMask[envelopeTimbre]&bit)==0||(envelopeKnownMask[envelopeTimbre]&bit)==0)return EnvelopeState.FACTORY;
        return envelopeValues[envelopeTimbre][envelopeMidi-60];
    }

    private void readEnvelopeNoteIfNeeded(){
        int key=envelopeAddress(envelopeTimbre,envelopeMidi);
        if(envelopeDialog==null||!envelopeReady()||envelopeNoteKnown()||envelopeEdits.containsKey(key))return;
        if(envelopePending!=null&&envelopePending.bank==envelopeTimbre&&envelopePending.midi==envelopeMidi)return;
        queueEnvelope(0,envelopeMidi,EnvelopeState.FACTORY,true);
    }

    private int currentEnvelopeMask(boolean ready){
        if(ready)return envelopeModifiedMask[envelopeTimbre];
        int mask=0;
        for(int midi=60;midi<=87;midi++)
            if(envelopeDrafts.getOrDefault(envelopeAddress(envelopeTimbre,midi),EnvelopeState.FACTORY)!=EnvelopeState.FACTORY)
                mask|=EnvelopeState.bit(midi);
        return mask;
    }

    private void updateEnvelopeUi(){
        boolean ready=envelopeReady();
        if(envelopeButton!=null)envelopeButton.setText("塑造音色 · 单音包络");
        if(envelopeStatus==null)return;
        int mask=currentEnvelopeMask(ready);
        boolean pending=envelopePending!=null||!envelopeEdits.isEmpty();
        int value=envelopeDisplayed();
        envelopeNote.setText(EnvelopeState.name(envelopeMidi)+" ▾");
        envelopeBankButton.setText(ModelState.name(envelopeTimbre,true)+" ▾");
        StringBuilder edited=new StringBuilder("已修改");
        for(int n=60;n<=87;n++)if((mask&EnvelopeState.bit(n))!=0)edited.append("\n").append(EnvelopeState.name(n));
        if(mask==0)edited.append("\n—");
        envelopeModified.setText(edited);
        envelopeModified.setContentDescription(edited.toString());
        String live=!ready?"离线预览 · 未写入乐器":pending?"正在同步音色参数…":!envelopeNoteKnown()?"等待此音参数回读":
                (mask&EnvelopeState.bit(envelopeMidi))==0?"此音使用原厂参数":"此音已自定义";
        if(ready&&!pending&&envelopeState.ticket==envelopeTicket&&envelopeTicket!=0){
            if(envelopeState.result==4)live="本次更新未确认 · "+live;
            else if(envelopeState.result==6)live="自定义已满，请先恢复一个音";
        }
        envelopeStatus.setText(live);
        envelopeReset.setEnabled((mask&EnvelopeState.bit(envelopeMidi))!=0 || pending);
        envelopeResetAll.setEnabled(ready || !envelopeDrafts.isEmpty());
        for(int i=0;i<envelopeBars.length;i++){
            int option=(value>>EnvelopeState.SHIFTS[i])&EnvelopeState.MASKS[i];
            envelopeBars[i].setEnabled((!ready||envelopeNoteKnown())&&(i!=1||envelopeTimbre<2));
            envelopeBars[i].setProgress(option);
            envelopeLabels[i].setText(i==0?"起音时长  "+EnvelopeState.ATTACK[option]:
                    i==2?"拨弦激励  "+EnvelopeState.FORCE[option]:
                    envelopeTimbre>=2?"琴体共鸣  · 此音色不使用琴体":"琴体共鸣  "+EnvelopeState.BODY[option]);
        }
        int curveKey=envelopeMidi|(envelopeTimbre<<7)|(envelopeCapo<<9)|(value<<13)|((envelopeDuration==600?1:0)<<21);
        if(envelopeModel!=null&&curveKey!=envelopeCurveKey){
            envelopeCurveKey=curveKey;
            envelopeCurve.setPreview(envelopeModel.preview(envelopeMidi,envelopeTimbre,envelopeCapo,value,envelopeDuration),envelopeDuration,envelopeMidi);
        }
    }

    private void queueEnvelope(int op,int midi,int value,boolean immediate){
        int bank=envelopeTimbre,key=envelopeAddress(bank,midi);
        if(!envelopeReady()){
            if(op==1){
                if(value==EnvelopeState.FACTORY)envelopeDrafts.remove(key);else envelopeDrafts.put(key,value);
            }else if(op==3)envelopeDrafts.clear();
            updateEnvelopeUi();
            return;
        }
        if(op==3)envelopeEdits.clear();
        envelopeEdits.put(op==3?-1:key,new EnvelopeEdit(op,bank,midi,value));
        if(immediate){
            main.removeCallbacks(envelopeFlush);envelopeFlushScheduled=false;flushEnvelope();
        }else if(!envelopeFlushScheduled){
            envelopeFlushScheduled=true;main.postDelayed(envelopeFlush,70);
        }
        updateEnvelopeUi();
    }

    private void flushEnvelope(){
        if(!envelopeReady()||envelopePending!=null||envelopeEdits.isEmpty())return;
        java.util.Iterator<EnvelopeEdit> next=envelopeEdits.values().iterator();
        EnvelopeEdit edit=next.next();next.remove();
        int ticket=envelopeTicket%32767+1;
        if(!writeCommand("ENV:"+edit.op+","+edit.bank+","+edit.midi+","+edit.value+","+ticket)){
            updateEnvelopeUi();toast("连接繁忙，请重试");return;
        }
        envelopeTicket=ticket;envelopePending=edit;updateEnvelopeUi();
        main.postDelayed(()->{
            if(envelopePending==edit){envelopePending=null;flushEnvelope();updateEnvelopeUi();toast("等待音色参数回读超时");}
        },3100);
    }

    private void selectEnvelopeNote(int midi,int model,int capo){
        if(!EnvelopeState.playable(midi))return;
        envelopeMidi=midi;envelopeTimbre=model;envelopeCapo=capo;
        updateEnvelopeUi();readEnvelopeNoteIfNeeded();
    }

    private void chooseEnvelopeNote(){
        java.util.List<AppDialogs.Item> pitches=new ArrayList<>();
        int mask=currentEnvelopeMask(envelopeReady());
        for(int midi=60;midi<=87;midi++)pitches.add(new AppDialogs.Item(EnvelopeState.name(midi),null,false,
                (mask&EnvelopeState.bit(midi))!=0?"已修改":midi==envelopeMidi?"当前":null));
        AppDialogs.list(this,"选择音高",pitches,i->selectEnvelopeNote(60+i,envelopeTimbre,envelopeCapo),"返回",null,null,null);
    }

    private void openEnvelope(){
        if(envelopeDialog!=null)return;
        if(envelopeModel==null){
            try(java.io.InputStream in=getResources().openRawResource(R.raw.original_envelope_model)){
                envelopeModel=new EnvelopeModel(in);
            }catch(java.io.IOException|android.content.res.Resources.NotFoundException e){toast("包络模型数据无法读取");}
        }
        android.app.Dialog dialog=new android.app.Dialog(this,android.R.style.Theme_Material_NoActionBar);
        envelopeDialog=dialog;
        LinearLayout page=column();
        page.setBackgroundColor(cSurface());
        LinearLayout header=row();
        header.setPadding(dp(20),dp(12),dp(20),dp(8));
        header.addView(kit.textBold("单音包络",ts(24),cTextPrimary()),new LinearLayout.LayoutParams(0,-2,1));
        TextView close=outlineAction("完成");close.setOnClickListener(v->dialog.dismiss());header.addView(close);
        page.addView(header,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout body=column();body.setPadding(dp(20),dp(4),dp(20),dp(20));
        body.addView(hint("拨弦选音，也可以手动选音色。拖动即时预览，连接时自动写入。曲线以原厂峰值为 100%。"));
        envelopeBankButton=outlineAction(ModelState.name(envelopeTimbre,true)+" ▾");
        envelopeBankButton.setOnClickListener(v->{
            java.util.List<AppDialogs.Item> banks=new ArrayList<>();
            for(int i=0;i<4;i++)banks.add(new AppDialogs.Item(ModelState.name(i,true),null,false,i==envelopeTimbre?"当前":null));
            AppDialogs.list(this,"编辑音色",banks,i->selectEnvelopeNote(envelopeMidi,i,envelopeCapo),"返回",null,null,null);
        });
        addPracticeButtonRow(body,6,envelopeBankButton);
        envelopeNote=outlineAction(EnvelopeState.name(envelopeMidi)+" ▾");
        envelopeNote.setOnClickListener(v->chooseEnvelopeNote());
        TextView zoom=outlineAction(envelopeDuration==600?"细看起音":"完整包络");
        zoom.setOnClickListener(v->{envelopeDuration=envelopeDuration==600?60:600;zoom.setText(envelopeDuration==600?"细看起音":"完整包络");updateEnvelopeUi();});
        addPracticeButtonRow(body,8,envelopeNote,zoom);
        LinearLayout plot=row();
        envelopeCurve=new EnvelopeCurveView(this);envelopeCurveKey=-1;
        plot.addView(envelopeCurve,new LinearLayout.LayoutParams(0,dp(174),1));addSpace(plot,8);
        envelopeModified=kit.text("",ts(12),cAccent());
        envelopeModified.setGravity(Gravity.CENTER_HORIZONTAL);
        envelopeModified.setBackground(kit.bg(cSurface2(),10));
        envelopeModified.setPadding(dp(4),dp(7),dp(4),dp(6));
        envelopeModified.setMaxLines(8);
        envelopeModified.setEllipsize(android.text.TextUtils.TruncateAt.END);
        envelopeModified.setOnClickListener(v->chooseEnvelopeNote());
        plot.addView(envelopeModified,new LinearLayout.LayoutParams(dp(64),dp(174)));
        LinearLayout.LayoutParams graphSize=new LinearLayout.LayoutParams(-1,-2);graphSize.topMargin=dp(10);body.addView(plot,graphSize);
        envelopeBars=new SeekBar[3];envelopeLabels=new TextView[3];
        for(int i=0;i<3;i++){
            final int control=i;
            TextView label=settingLabel("",dp(10));body.addView(label);envelopeLabels[i]=label;
            SeekBar bar=kit.seekBar(cAccent());bar.setMax(EnvelopeState.MASKS[i]);envelopeBars[i]=bar;
            bar.setContentDescription(i==0?"起音时长":i==1?"琴体共鸣":"拨弦激励");body.addView(bar);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                private int target;
                public void onProgressChanged(SeekBar view,int value,boolean user){
                    if(!user)return;
                    int key=envelopeAddress(envelopeTimbre,envelopeMidi);
                    if(target!=0&&target!=key)return;
                    int shift=EnvelopeState.SHIFTS[control],mask=EnvelopeState.MASKS[control];
                    queueEnvelope(1,envelopeMidi,(envelopeDisplayed()&~(mask<<shift))|value<<shift,false);
                }
                public void onStartTrackingTouch(SeekBar view){target=envelopeAddress(envelopeTimbre,envelopeMidi);}
                public void onStopTrackingTouch(SeekBar view){
                    target=0;
                    main.removeCallbacks(envelopeFlush);envelopeFlushScheduled=false;flushEnvelope();
                }
            });
        }
        envelopeStatus=hint("");envelopeStatus.setPadding(0,dp(10),0,0);body.addView(envelopeStatus);
        envelopeReset=outlineAction("恢复这个音");envelopeResetAll=outlineAction("恢复全部");
        envelopeReset.setOnClickListener(v->queueEnvelope(1,envelopeMidi,EnvelopeState.FACTORY,true));
        envelopeResetAll.setOnClickListener(v->AppDialogs.message(this,"恢复全部包络","所有改过的音都会回到原厂参数。",
                "恢复全部",(d,w)->queueEnvelope(3,envelopeMidi,EnvelopeState.FACTORY,true),"取消",null,null,null));
        addPracticeButtonRow(body,8,envelopeReset,envelopeResetAll);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(page);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d->{envelopeDialog=null;envelopeStatus=null;envelopeCurve=null;envelopeBars=null;envelopeLabels=null;});
        dialog.show();
        android.view.Window window=dialog.getWindow();
        AppTheme.applyWindow(window);
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
        updateEnvelopeUi();readEnvelopeNoteIfNeeded();
    }

    private boolean modelReady(){return bleReady&&powerAllowsControls()&&!modelExpired&&modelState!=null&&modelState.fresh();}

    private void requestModel(int index){
        if(!modelReady()||index<0||index>=MODEL_FEEL.length)return;
        if(modelPending?pendingModel==index:modelState.actual==index)return;
        int ticket=modelTicket%32767+1;
        if(!writeCommand("MODEL:"+index+","+ticket))return;
        modelTicket=ticket;pendingModel=index;modelPending=true;modelFeedback=null;updateModelUi();
        main.postDelayed(()->{if(modelPending&&modelTicket==ticket){
            modelPending=false;pendingModel=-1;modelFeedback="切换超时，请重试";updateModelUi();writeCommand("MODEL_GET");
        }},4000);
    }

    private void updateModelUi(){
        if(modelStatus==null)return;
        boolean ready=modelReady();
        int selected=modelPending?pendingModel:ready?modelState.actual:-1;
        for(int i=0;i<modelChoices.size();i++){
            TextView button=modelChoices.get(i);
            String feel=!modernModelNames()&&i>=2?(i==2?"明亮颗粒":"弹性起音"):MODEL_FEEL[i];
            String label=ModelState.name(i,modernModelNames())+"\n"+feel;
            if(!label.contentEquals(button.getText()))button.setText(label);
            button.setEnabled(ready);setSegment(button,selected==i);
        }
        String actual=ready?ModelState.name(modelState.actual,modernModelNames()):"等待 FPGA 音色回读";
        modelStatus.setText(!bleReady?"连接乐器后可切换":modelPending?"正在切换到 "+ModelState.name(pendingModel,modernModelNames())+"…":
            modelFeedback!=null?modelFeedback+(ready?" · 当前 "+actual:""):ready?"当前音色 · "+actual:actual);
    }

    private void receiveModel(ModelState state){
        if(!state.acceptsAfter(modelState))return;
        modelState=state;modelExpired=false;main.removeCallbacks(modelExpiry);main.postDelayed(modelExpiry,2000);
        if(modelPending&&state.ticket==modelTicket&&state.result>=2){
            boolean applied=state.fresh()&&state.actual==pendingModel&&(state.result==2||state.result==6);
            if(applied||state.result>=3&&state.result<=5){
                modelPending=false;pendingModel=-1;
                modelFeedback=applied?(state.result==6?"音色已生效，设备保存失败":null):"音色未确认，请重试";
            }
        }
        updateModelUi();
    }

    private int activeCapo(){return confirmedCapo;}
    private int boardCapo(){return capoState!=null&&capoState.physicalCapo()?confirmedCapo:0;}
    private void addCapoControls(LinearLayout body){
        LinearLayout box=card();capoActual=kit.textBold("乐器当前 · 等待连接",ts(14),cAccent());box.addView(capoActual);
        capoBoard=new com.ukulele.monitor.ui.CapoFretboardView(this);LinearLayout.LayoutParams diagram=new LinearLayout.LayoutParams(-1,dp(180));diagram.topMargin=dp(8);box.addView(capoBoard,diagram);
        LinearLayout step=row();capoMinus=outlineAction("−");capoPlus=outlineAction("＋");
        capoMinus.setContentDescription("变调夹向琴枕移动一品");capoPlus.setContentDescription("变调夹向琴身移动一品");
        capoLabel=kit.textBold("未夹 · 原调",ts(20),cTextPrimary());capoLabel.setGravity(Gravity.CENTER);capoLabel.setMinHeight(dp(54));
        step.addView(capoMinus,new LinearLayout.LayoutParams(dp(52),-2));step.addView(capoLabel,new LinearLayout.LayoutParams(0,-2,1));step.addView(capoPlus,new LinearLayout.LayoutParams(dp(52),-2));box.addView(step);
        capoBar=kit.seekBar(cAccent());capoBar.setMax(12);box.addView(capoBar,new LinearLayout.LayoutParams(-1,dp(48)));
        capoBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){capoDragging=true;}
            public void onProgressChanged(SeekBar b,int n,boolean user){if(user)capoDraftDirty=true;updateCapoDraft();}
            public void onStopTrackingTouch(SeekBar b){capoDragging=false;}
        });
        capoMinus.setOnClickListener(v->{capoDraftDirty=true;capoBar.setProgress(Math.max(0,capoBar.getProgress()-1));});
        capoPlus.setOnClickListener(v->{capoDraftDirty=true;capoBar.setProgress(Math.min(12,capoBar.getProgress()+1));});
        android.widget.HorizontalScrollView positions=new android.widget.HorizontalScrollView(this);positions.setHorizontalScrollBarEnabled(false);LinearLayout rail=row();
        capoChoices.clear();for(int i=0;i<=12;i++){final int n=i;TextView b=outlineAction(n==0?"不夹":n+"品");b.setMinHeight(dp(48));if(i>0)addSpace(rail,8);rail.addView(b,new LinearLayout.LayoutParams(dp(68),-2));capoChoices.add(b);b.setOnClickListener(v->{capoDraftDirty=true;capoBar.setProgress(n);});}positions.addView(rail);box.addView(positions,new LinearLayout.LayoutParams(-1,-2));
        capoPlayable=kit.text("",ts(13),cTextSecondary());capoPlayable.setPadding(0,dp(12),0,dp(12));box.addView(capoPlayable);
        capoApply=outlineAction("应用到乐器");capoReset=outlineAction("卸下变调夹");capoApply.setMinHeight(dp(52));capoReset.setMinHeight(dp(52));
        box.addView(capoApply,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams reset=new LinearLayout.LayoutParams(-1,-2);reset.topMargin=dp(8);box.addView(capoReset,reset);
        capoApply.setOnClickListener(v->requestCapo(capoBar.getProgress()));capoReset.setOnClickListener(v->{capoBar.setProgress(0);capoDraftDirty=true;requestCapo(0);});
        capoStatus=kit.text("连接乐器后读取变调夹",ts(12),cTextSecondary());capoStatus.setPadding(0,dp(10),0,0);box.addView(capoStatus);
        addContent(body,box);updateCapoDraft();updateCapoUi();
    }
    private void updateCapoDraft(){
        if(capoBar==null)return;int n=capoBar.getProgress();capoLabel.setText(n==0?"未夹 · 原调":"第 "+n+" 品");
        if(capoBoard!=null)capoBoard.setFret(n);if(capoPlayable!=null)capoPlayable.setText(n==0?"全部18品可演奏":(n+1)+"—18品可演奏 · 夹持区触摸停用");
        for(int i=0;i<capoChoices.size();i++)setSegment(capoChoices.get(i),i==n);
        capoMinus.setEnabled(n>0&&!capoPending);capoPlus.setEnabled(n<12&&!capoPending);
    }
    private void updateCapoUi(){
        if(capoBar==null)return;
        boolean ready=bleReady&&powerAllowsControls()&&capoState!=null&&capoState.fresh()&&capoState.physicalCapo()&&!capoExpired&&!capoPending;
        capoBar.setEnabled(!capoPending);for(TextView b:capoChoices)b.setEnabled(!capoPending);capoApply.setEnabled(ready);capoReset.setEnabled(ready);updateCapoDraft();
        capoActual.setText(!bleReady?"乐器当前 · 未连接":capoState==null||!capoState.fresh()?"乐器当前 · 等待读取":"乐器当前 · "+(confirmedCapo==0?"未夹":confirmedCapo+"品")+(capoExpired?"（上次确认）":""));
        capoStatus.setText(!bleReady?"可预览位置 · 连接乐器后应用":capoPending?"等待乐器确认":capoExpired||capoState==null?"正在读取变调夹":capoState.status());
    }
    private void requestCapo(int n){
        if(!bleReady||!powerAllowsControls()||capoState==null||!capoState.fresh()||!capoState.physicalCapo()||capoExpired||capoPending)return;
        capoTicket=capoTicket%32767+1;final int id=capoTicket;
        if(!writeCommand("CAPO:"+n+","+id))return;
        capoPending=true;updateCapoUi();if(capoTimeout!=null)main.removeCallbacks(capoTimeout);
        capoTimeout=()->{if(capoPending&&capoTicket==id){capoPending=false;updateCapoUi();capoStatus.setText("未收到确认，请重试");}};main.postDelayed(capoTimeout,4000);
    }
    private void applyCapoState(CapoState value){
        if(!value.acceptsAfter(capoState))return;
        boolean changed=capoState==null||value.actual!=capoState.actual;
        if(capoState!=null&&value.actual!=capoState.actual){abortScoring("变调夹已改变，请重新开始练习");stopScoreAudition();}
        capoState=value;if(value.fresh()&&value.actual<=12)confirmedCapo=value.actual;capoExpired=false;main.removeCallbacks(capoExpiry);main.postDelayed(capoExpiry,2000);
        if(capoPending&&value.ticket==capoTicket&&value.result>=2){capoPending=false;if(value.result==2)capoDraftDirty=false;if(capoTimeout!=null)main.removeCallbacks(capoTimeout);changed=true;}
        if(changed&&!capoDraftDirty&&!capoDragging&&capoBar!=null&&value.actual<=12)capoBar.setProgress(value.actual);
        updateCapoUi();if(ledPreview!=null)ledPreview.setCapo(boardCapo());if(songGuideView!=null)songGuideView.setCapo(boardCapo());
    }

    private void updateGestureUi(){
        if(glideSwitch==null || vibratoSwitch==null)return;
        updatingGestureUi=true;
        int mask=gestureState==null?3:(gestureState.waiting()?gestureState.desired:
            gestureState.actual<=7?gestureState.actual:gestureState.desired);
        glideSwitch.setChecked((mask&1)!=0);vibratoSwitch.setChecked((mask&2)!=0);
        boolean ready=bleReady && powerAllowsControls() && !gestureExpired && gestureState!=null && gestureState.fresh() &&
            !gestureState.waiting() && !gesturePending;
        glideSwitch.setEnabled(ready);vibratoSwitch.setEnabled(ready);
        if (vibratoSourceButton != null) {
            boolean adjustable = ready && gestureState.selectableSource();
            vibratoSourceButton.setEnabled(adjustable);
            vibratoSourceButton.setAlpha(adjustable ? 1f : .55f);
            vibratoSourceButton.setText(gestureState != null && gestureState.fresh()
                    ? "颤音判定 · " + gestureState.sourceName() + (adjustable ? " ▾" : "") : "颤音判定 · 等待回读");
        }
        if(gestureStatus!=null)gestureStatus.setText(!bleReady?"连接设备后读取实际开关状态":gesturePending?"等待 FPGA 确认":
            gestureExpired?"手势状态已过期，等待设备回读":gestureState==null?"尚未收到手势回读，请更新配套 FPGA/ESP32 固件":gestureState.status());
        updatingGestureUi=false;
    }
    private void chooseVibratoSource() {
        if (gesturePending || gestureExpired || gestureState == null ||
                !gestureState.fresh() || !gestureState.selectableSource()) return;
        ArrayList<AppDialogs.Item> options=new ArrayList<>();
        boolean auxiliary=(gestureState.actual&4)!=0;
        options.add(new AppDialogs.Item("辅助电容揉弦", "按辅助铜片的往复变化判定", false, auxiliary?"当前":null));
        options.add(new AppDialogs.Item("原品格判定", "按当前品格与同弦品格的电容变化判定", false, auxiliary?null:"当前"));
        AppDialogs.list(this,"颤音判定",options,index -> {
            if (!gesturePending && gestureState != null && gestureState.selectableSource())
                requestGestureChange(index==0?4:0);
        },"返回",null,null,null);
    }

    private void requestGestureChange(){
        requestGestureChange(gestureState != null && gestureState.selectableSource() && gestureState.actual<=7
                ? gestureState.actual&4 : 0);
    }

    private void requestGestureChange(int source){
        if(updatingGestureUi || gesturePending)return;
        if(!bleReady || !powerAllowsControls() || gestureExpired || gestureState==null ||
                !gestureState.fresh() || gestureState.waiting()){updateGestureUi();return;}
        int mask=source|(glideSwitch.isChecked()?1:0)|(vibratoSwitch.isChecked()?2:0);
        gestureTicket=gestureTicket%65535+1;
        if(!writeCommand("GESTURE:"+mask+","+gestureTicket)){updateGestureUi();return;}
        gesturePending=true;glideSwitch.setEnabled(false);vibratoSwitch.setEnabled(false);
        updateGestureUi();
        if(gestureTimeout!=null)main.removeCallbacks(gestureTimeout);
        final int expected=gestureTicket;
        gestureTimeout=()->{if(gesturePending && expected==gestureTicket){gesturePending=false;updateGestureUi();gestureStatus.setText("未收到操作确认，请检查连接后重试");}};
        main.postDelayed(gestureTimeout,3500);
    }
    private void applyGestureState(GestureState value){
        if(!value.acceptsAfter(gestureState))return;
        gestureState=value;gestureExpired=false;main.removeCallbacks(gestureStateExpired);main.postDelayed(gestureStateExpired,2000);
        if(gesturePending && value.ticket==gestureTicket && value.result!=0 && value.result!=1){
            gesturePending=false;if(gestureTimeout!=null)main.removeCallbacks(gestureTimeout);
        }
        updateGestureUi();updateTouchUi();updateChainUi();updateCapoUi();
    }

    private void selectPlayMode(int mode){
        if(controllerState!=null && controllerState.version<2){toast("当前 ESP32 固件不支持演奏模式，请更新配套固件");return;}
        if(writeCommand("PLAYMODE:"+mode+","+(++playModeTicket&255)))modeStatusText.setText("等待 FPGA 确认"+(mode==0?"一拍锁定":"四弦复音"));
    }

    /** @return true 表示 LED 指令已成功入队；false 时调用方不应再提示“已应用”。 */
    private boolean applyLed() {
        if(bitmapPendingTicket!=0){toast("灯光配置正在确认，请稍候");return false;}
        if(selectedLedMode==8){
            if(bitmapState==null){toast("请先读取自定义图案");return false;}
            return sendBitmap(new LedProfile(bitmapState.profile.data,ledBrightness));
        }
        if (writeCommand("LED:" + selectedLedMode + "," + selectedLedAxis + "," + ledSpeed + "," + ledBrightness + "," + ledFlags)) {
            ledDraftDirty = true;
            unlockBadge(ACH_FIRST_LED);
            return true;
        }
        return false;
    }

    /**
     * 应用一套灯效场景：先同步界面控件到场景值，再走原来的 applyLed() 指令。
     * 场景只是“预置组合”，不是新协议，因此设备端行为与手动调参完全一致。
     */
    private void applyLedScene(int[] params) {
        if (params == null || params.length != 4) return;
        tap();
        selectedLedMode = params[0];
        selectedLedAxis = params[1];
        ledSpeed = params[2];
        ledBrightness = params[3];
        if (ledModeButtons != null) {
            for (int i = 0; i < ledModeButtons.length; ++i) setSegment(ledModeButtons[i], i == selectedLedMode);
        }
        if (rowAxisButton != null) setSegment(rowAxisButton, selectedLedAxis == 0);
        if (colAxisButton != null) setSegment(colAxisButton, selectedLedAxis == 1);
        if (speedBar != null) speedBar.setProgress(ledSpeed);
        if (speedValue != null) speedValue.setText(String.valueOf(ledSpeed));
        if (brightnessBar != null) brightnessBar.setProgress(ledBrightness);
        if (brightnessValue != null) brightnessValue.setText(String.valueOf(ledBrightness));
        // 只有指令真的入队才提示“已应用”；离线/休眠时 writeCommand 已给出原因，这里不再补一条假成功。
        if (applyLed()) toast("已应用灯效场景");
        invalidateLedPreview();
    }

    private void openBitmapEditor(){
        if(bitmapEditor!=null&&bitmapEditor.isShowing())return;
        bitmapEditor=new LedBitmapEditor(this,bitmapState!=null&&(bitmapState.flags&64)!=0?bitmapState.profile:null,new LedBitmapEditor.Listener(){
            public boolean apply(LedProfile p){return sendBitmap(p);}
            public void refresh(){writeCommand("LM_GET");}
            public void ai(){openAiPixelProfile();}
        });bitmapEditor.show();updateBitmapUi();if(bleReady)writeCommand("LM_GET");
    }
    private boolean sendBitmap(LedProfile profile){
        if(bitmapPendingTicket!=0){toast("上一幅配置还在确认，请稍候");return false;}
        if(profile.hasPulse()&&(bitmapState==null||(bitmapState.caps&8)==0)){toast("星光与流星需要新版FPGA及ESP32固件，请先更新配套固件");return false;}
        if(!bleReady||!powerAllowsControls()||bitmapState==null||!bitmapState.fresh()){toast("请连接并唤醒设备，确认使用配套固件");return false;}
        if((bitmapState.flags&16)!=0){toast("请先停止曲谱灯光指引");return false;}
        bitmapTicket=LedBitmapState.nextTicket(bitmapTicket,bitmapState.ticket);final int ticket=bitmapTicket;bitmapPendingTicket=ticket;bitmapPendingProfile=profile;
        profilePackets.clear();profilePackets.addAll(profile.commands(ticket));main.post(profileSender);
        bitmapTimeout=()->{if(bitmapPendingTicket==ticket){bitmapPendingTicket=0;bitmapPendingProfile=null;profilePackets.clear();main.removeCallbacks(profileSender);if(bitmapStatusText!=null)bitmapStatusText.setText("应用超时 · 配置未确认，请重试");if(bitmapEditor!=null&&bitmapEditor.isShowing())bitmapEditor.update("应用超时 · 请重试",bleReady,null);}};
        main.postDelayed(bitmapTimeout,16000);updateBitmapUi();return true;
    }
    private void applyBitmapState(LedBitmapState b){
        if(!b.newer(bitmapState))return;bitmapState=b;
        boolean confirmed=b.confirms(bitmapPendingTicket,bitmapPendingProfile);
        if(bitmapPendingTicket!=0&&b.ticket==bitmapPendingTicket&&(confirmed||(b.result>=3&&b.result<=6))){
            bitmapPendingTicket=0;bitmapPendingProfile=null;profilePackets.clear();main.removeCallbacks(profileSender);if(bitmapTimeout!=null)main.removeCallbacks(bitmapTimeout);
            if(confirmed){
                selectedLedMode=8;ledBrightness=b.level;ledDraftDirty=false;
                if(brightnessBar!=null)brightnessBar.setProgress(b.level);
                if(brightnessValue!=null)brightnessValue.setText(String.valueOf(b.level));
                if(ledModeButtons!=null)for(TextView v:ledModeButtons)setChip(v,false);
            }
        }
        invalidateLedPreview();updateBitmapUi();
    }
    private void updateBitmapUi(){
        String text=!bleReady?"未连接 · 可编辑并保存本地图案":bitmapPendingTicket!=0?"正在应用 · 等待FPGA确认":bitmapState==null?"等待设备返回自定义灯阵状态":bitmapState.label();
        if(bitmapStatusText!=null)bitmapStatusText.setText(text);
        if(bitmapEditor!=null&&bitmapEditor.isShowing())bitmapEditor.update(text,bitmapPendingTicket==0&&bleReady&&powerAllowsControls()&&bitmapState!=null&&bitmapState.fresh()&&(bitmapState.flags&16)==0,bitmapState!=null&&bitmapState.fresh()&&(bitmapState.flags&64)!=0?bitmapState.profile:null);
    }

    private void setControlsEnabled(boolean enabled) {
        updateBitmapUi();
        enabled=enabled && powerAllowsControls();
        for (View v : new View[]{volumeBar, ukeButton, guitarButton, rowAxisButton, colAxisButton, speedBar, brightnessBar, ledApplyButton}) if (v != null) v.setEnabled(enabled);
        if (ledModeButtons != null) for (TextView v : ledModeButtons) if (v != null) v.setEnabled(enabled);
        for (TextView v : presetButtons) if (v != null) v.setEnabled(enabled);
        if(modeLockButton!=null)modeLockButton.setEnabled(enabled);
        if(modePolyButton!=null)modePolyButton.setEnabled(enabled);
        updateGestureUi();updateTouchUi();updateChainUi();updateCapoUi();
        updateScoringUi();
        updateSongPlayState();
    }

    private void applyControllerState(ControllerState state) {
        if (gatt == null || !state.isNewerThan(controllerState)) return;
        controllerState = state; settingsSynced = true;
        if(!state.fpgaOnline()){
            playedNoteTracker.reset();abortScoring("FPGA离线，本轮评分中止");
            liveNoteState=null;currentNote="--";updateNoteLabels();
            clearAudioTelemetry();
            if(peakMetric!=null)peakMetric.setText("-- Hz");
            if(sampleMetric!=null)sampleMetric.setText("FPGA 离线");
            if(visualizer!=null)visualizer.clear();
        }
        if (stateSyncTimeout != null) main.removeCallbacks(stateSyncTimeout);
        if(stateSyncRetry!=null)main.removeCallbacks(stateSyncRetry);
        setControlsEnabled(bleReady);
        if(ledPreview!=null && !state.songPlaying())ledPreview.clearGuide();
        updateSongPlayState();
        refreshVisualizer();
        updateDeviceInfo();
        if (!volumeDragging) { currentVolume = state.volume; volumeBar.setProgress(state.volume); volumeValue.setText(String.valueOf(state.volume)); }
        setChip(modeLockButton,state.fpgaOnline() && state.actualMode==0);
        setChip(modePolyButton,state.fpgaOnline() && state.actualMode==1);
        modeStatusText.setText(state.modeStatus());operationStatusText.setText(state.operationStatus());
        String[] presetNames={"灵敏优先","均衡","抗误报","抗环境光"};
        presetStatusText.setText(!state.fpgaOnline()?"当前预设：离线":state.optoPreset<4?"当前预设："+presetNames[state.optoPreset]:"当前预设：自定义或等待完整回读");
        updatePowerUi();
        currentTimbre = state.timbre; updateModelUi();
        boolean ledMatches = selectedLedMode == state.ledMode && selectedLedAxis == state.ledAxis && ledSpeed == state.speed && ledBrightness == state.brightness && ledFlags == state.ledFlags;
        if (ledMatches) ledDraftDirty = false;
        if (!ledDraftDirty) {
            selectedLedMode = state.ledMode; selectedLedAxis = state.ledAxis; ledSpeed = state.speed; ledBrightness = state.brightness; ledFlags = state.ledFlags;
            for (int i = 0; i < ledModeButtons.length; ++i) setChip(ledModeButtons[i], i == selectedLedMode);
            setChip(rowAxisButton, selectedLedAxis == 0); setChip(colAxisButton, selectedLedAxis == 1);
            speedBar.setProgress(ledSpeed); brightnessBar.setProgress(ledBrightness);
            speedValue.setText(String.valueOf(ledSpeed)); brightnessValue.setText(String.valueOf(ledBrightness));
            invalidateLedPreview();
        }
        for (int i = 0; i < presetButtons.length; ++i) if (presetButtons[i] != null) setChip(presetButtons[i], i == state.optoPreset);
        setConnectionStatus(!state.fpgaOnline() ? "已连接 · FPGA 离线" : state.audioSettingsApplied() && (state.version==1 || state.playModeConfirmed()) ? "已连接 · 实际设置已确认" : "已连接 · 等待实际回读");
    }

    /** 用户主动断开 / 主动重连前清理；会取消待定的自动重连。 */
    private void disconnect() {
        cancelAutoReconnect();
        disconnectInternal();
    }

    /** 只做清理，不碰自动重连计数（供重连流程自己使用）。 */
    private void disconnectInternal() {
        guideSoftKnown=false;updateGuideFadeUi();
        chainState=null;chainPending=false;chainFeedback=null;java.util.Arrays.fill(chainDragging,false);chainExpired=true;main.removeCallbacks(chainExpiry);updateChainUi();
        envelopeState=null;envelopePending=null;envelopeEdits.clear();clearEnvelopeCache();envelopeFlushScheduled=false;
        main.removeCallbacks(envelopeExpiry);main.removeCallbacks(envelopeFlush);updateEnvelopeUi();
        capoState=null;confirmedCapo=0;capoPending=false;capoExpired=true;modelState=null;modelPending=false;pendingModel=-1;modelFeedback=null;modelExpired=true;
        main.removeCallbacks(capoExpiry);main.removeCallbacks(modelExpiry);if(capoTimeout!=null)main.removeCallbacks(capoTimeout);
        updateCapoUi();updateModelUi();
        cancelFullCountdown();
        playedNoteTracker.reset();
        if(powerTimeout!=null)main.removeCallbacks(powerTimeout);
        powerState=null;selectedAnalysisSupported=false;batteryState=null;
        if(performanceTake!=null){performanceTake.stop();updateTakeUi();}
        main.removeCallbacks(batteryExpired);updateBatteryUi();main.removeCallbacks(practiceLease);
        touchState=null;pendingTouchEdit=null;touchExpired=false;touchThresholdDirty=false;touchTimingDirty=false;main.removeCallbacks(touchStateExpired);if(touchTimeout!=null)main.removeCallbacks(touchTimeout);
        fxState=null;fxExpired=true;main.removeCallbacks(fxStateExpired);
        gestureState=null;gesturePending=false;gestureExpired=false;main.removeCallbacks(gestureStateExpired);if(gestureTimeout!=null)main.removeCallbacks(gestureTimeout);
        clearAudioTelemetry();
        boolean hadDevicePlay = hasDeviceSongIntent();
        cancelPendingSongPlay(); hardwarePlayingId = null; activePlayToken = 0; activePlayId = null;
        songTempoSupported=false;deviceSongState=null;activeDeviceSession=0;deviceStopRequested=false;
        if(ledPreview!=null){polyGuideFrame=null;ledPreview.clearGuide();ledPreview.clearStrumEvents();}
        fullGuideAfterUploadId=null;guideUploadGeneration++;pendingUploadId = null; pendingUploadName = null; uploadedSongId = null;
        stopSongGuide(hadDevicePlay ? "连接已断开，本地引导已停止；设备停止状态未确认" : "本地跟弹预览已停止，未下发设备");
        if (songGuideView != null) songGuideView.clearDeviceEvent();
        if (songStatusLabel != null) songStatusLabel.setText("设备未连接 · 所选曲目需重新上传");

        if(uploadAckTimeout!=null)main.removeCallbacks(uploadAckTimeout);
        if (scanTimeout != null) { main.removeCallbacks(scanTimeout); scanTimeout = null; }
        // 断开时若扫描仍在进行，必须真正停掉它：只撤定时器会让 LOW_LATENCY 扫描无限期继续，
        // 既耗电又可能在之后扫到设备而「自动连上」，与用户点「断开」的意图相反。
        if (scanning) {
            if (oldScanner != null) { try { oldScanner.stopScan(scanCallback); } catch (Exception ignored) {} }
            scanning = false;
        }
        if (bleTimeout != null) main.removeCallbacks(bleTimeout);
        if (writeTimeout != null) { main.removeCallbacks(writeTimeout); }
        writeTimeout = null; writeWatchdogArmed = false;
        if (stateSyncTimeout != null) main.removeCallbacks(stateSyncTimeout);
        if(stateSyncRetry!=null)main.removeCallbacks(stateSyncRetry);
        liveNoteState=null;
        bitmapState=null;bitmapPendingTicket=0;bitmapPendingProfile=null;bitmapAssembler.clear();profilePackets.clear();main.removeCallbacks(profileSender);
        if(bitmapTimeout!=null)main.removeCallbacks(bitmapTimeout);updateBitmapUi();
        controllerState = null; settingsSynced = false; ledDraftDirty = false; volumeDragging = false; setControlsEnabled(false);
        BluetoothGatt old = gatt; gatt = null; bleReady = false; bleReadySince = 0; notificationsBusy = false; songWriteBusy = false; activeWrite = null; negotiatedMtu = 23;
        notifyQueue.clear(); commandQueue.clear(); songQueue.clear(); commandChar = null; songChar = null; settingsChar=null; writeStartRetries = 0;
        writeGeneration++;
        updateSongPlayState();updatePowerUi();updateGestureUi();updateTouchUi();updateChainUi();updateCapoUi();
        if (old != null) { try { old.disconnect(); old.close(); } catch (Exception ignored) {} }
    }

    // ---- 非预期掉线的自动恢复 ----
    private void cancelAutoReconnect() {
        if (reconnectRunnable != null) { main.removeCallbacks(reconnectRunnable); reconnectRunnable = null; }
        reconnectAttempts = 0;
    }

    /**
     * 在断开连接**之前**判断本次连接是否算「稳定在线」。
     * 必须在 disconnectInternal() 之前调用：那会清掉 bleReady/bleReadySince，
     * 之后再判断就永远是 false（早期版本的缺陷）。
     */
    private boolean connectionWasStable() {
        return bleReady && bleReadySince > 0 && SystemClock.uptimeMillis() - bleReadySince >= STABLE_CONNECTION_MS;
    }

    /**
     * 设备侧断开或链路丢失后的自动重连（仅在设置开启自动重连、应用在前台、且不是用户主动断开时）。
     * 有次数上限，失败若干次后放弃，避免后台反复重试耗电。
     *
     * 「连上就掉」的设备不会无限循环：只有本次连接**稳定在线满 {@link #STABLE_CONNECTION_MS}**
     * 才把计数清零（由调用方在断开前用 {@link #connectionWasStable()} 判定并传入）；
     * 否则计数会一路累加到上限后停止重连，必须由用户手动重连。
     *
     * @param previousWasStable 刚刚断掉的那次连接是否稳定在线过。
     */
    private void scheduleAutoReconnect(boolean previousWasStable) {
        // 「记功」必须在所有 guard 之前：若连接稳定后是在**后台**掉线，
        // 下面 !activityVisible 会早退，但这次稳定连接的信度不能丢，
        // 否则回前台再掉线时会白扣一次重连预算。
        if (previousWasStable) reconnectAttempts = 0;
        if (settings == null || !settings.autoConnect()) return;
        if (!activityVisible || gatt != null) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return;

        if (reconnectRunnable != null) main.removeCallbacks(reconnectRunnable);
        final int attempt = ++reconnectAttempts;
        final long delay = 2000L * attempt;   // 2s / 4s / 6s 递增，给设备与协议栈恢复时间
        setStatusText("连接已断开 · 正在重连（" + attempt + "/" + MAX_RECONNECT_ATTEMPTS + "）…");
        reconnectRunnable = () -> {
            reconnectRunnable = null;
            if (gatt != null || !activityVisible) return;
            if (settings == null || !settings.autoConnect()) return;
            scan(true);
        };
        main.postDelayed(reconnectRunnable, delay);
    }
    private void importVoicePack(final Uri uri){
        if(voicePackImporting)return;voicePackImporting=true;if(voicePackStatus!=null)voicePackStatus.setText("正在校验并保存语音包…");
        scoreWorker.execute(()->{String error=null;try(java.io.InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new java.io.IOException("无法打开文件");com.ukulele.monitor.ai.VoiceModelPack.install(this,in);}catch(Exception e){error=e.getMessage()==null?"导入失败":e.getMessage();}
            final String result=error;main.post(()->{voicePackImporting=false;if(isDestroyed())return;if(voicePackStatus!=null)voicePackStatus.setText(com.ukulele.monitor.ai.VoiceModelPack.status(this));toast(result==null?"语音包已导入，可返回音乐搭子使用麦克风":result);});});
    }
    private void pickSong() { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "text/plain", "text/csv", "text/tab-separated-values", "application/pdf", "image/*", "audio/midi", "audio/x-midi", "application/x-midi"}); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, REQ_FILE); }
    @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);
        if(req==REQ_PAINT_EXPORT){
            android.graphics.Bitmap art=paintToExport;paintToExport=null;
            if(art!=null && res==RESULT_OK && data!=null && data.getData()!=null){
                Uri target=data.getData();
                scoreWorker.execute(()->{
                    try(java.io.OutputStream out=getContentResolver().openOutputStream(target)){
                        if(out==null||!art.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))throw new java.io.IOException();
                        main.post(()->toast("画作已保存"));
                    }catch(Exception e){main.post(()->toast("画作保存失败"));}
                    finally{art.recycle();}
                });
            }else if(art!=null)art.recycle();
            return;
        }
        if(req==REQ_VOICE_PACK){if(res==RESULT_OK&&data!=null&&data.getData()!=null)importVoicePack(data.getData());return;}
        if(req==REQ_FILE&&res==RESULT_OK&&data!=null)handleSongUri(data.getData());
        else if(req==REQ_SCORE_VIEW){rebuildSavedScores();if(res==RESULT_OK&&data!=null){String id=data.getStringExtra("id");if(("saved:"+id).equals(pendingUploadId)){songQueue.clear();pendingUploadId=null;pendingUploadName=null;if(uploadAckTimeout!=null)main.removeCallbacks(uploadAckTimeout);}if(("saved:"+id).equals(uploadedSongId)){uploadedSongId=null;uploadDeviceConfirmed=false;}selectSavedScore(id,"practice".equals(data.getStringExtra("guide_action"))?()->{if(activityVisible&&selectedTimeline!=null){if(scoringEnabled&&!canScoreNow()){toast("指引已生成，连接乐器或关闭评分后开始练习");}else requestLocalPractice();}}:null);}}
        else if(req==REQ_SCORE_EXPORT&&res==RESULT_OK&&data!=null&&exportScoreId!=null){final Uri target=data.getData();final String id=exportScoreId;scoreWorker.execute(()->{try(java.io.InputStream in=new java.io.FileInputStream(scoreRepo.source(id));java.io.OutputStream out=getContentResolver().openOutputStream(target)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);main.post(()->toast("原谱已导出"));}catch(Exception e){main.post(()->toast("导出失败"));}});}
        // 「我的曲库」返回：选中某首 → 载入练习；点「导入曲谱」→ 走本页既有导入流程。
        else if(req==REQ_SCORE_LIBRARY){
            if(res==ScoreLibraryActivity.RESULT_RECORD)showRecorder();
            else if(res==RESULT_FIRST_USER)pickSong();
            else if(res==RESULT_OK&&data!=null){String id=data.getStringExtra(ScoreLibraryActivity.EXTRA_SELECTED_ID);if(id!=null&&!id.isEmpty()){selectSavedScore(id);showSongSection(true);}}
            rebuildSavedScores();
        }
        else if(req==REQ_AI_IMAGE){handleAiImageResult(res,data);}
        else if(req==REQ_AI_DOCUMENT){handleAiDocumentResult(res,data);}
        else if(req==REQ_BROWSER_SCORE){
            Runnable selected=pendingBrowserScoreSelected;pendingBrowserScoreSelected=null;
            if(res==RESULT_OK&&data!=null&&data.getData()!=null){
                if(selected!=null)selected.run();
                handleSongUri(data.getData());
            }
        }
    }

    private String displayName(Uri uri) {
        if (uri == null) return "曲谱文件";
        try (Cursor c = getContentResolver().query(uri, new String[]{"_display_name"}, null, null, null)) { if (c != null && c.moveToFirst()) return c.getString(0); } catch (Exception ignored) {}
        String path = uri.getLastPathSegment(); return path == null ? "曲谱文件" : path;
    }

    private boolean isPdf(Uri uri) {
        String type = getContentResolver().getType(uri); return "application/pdf".equalsIgnoreCase(type) || displayName(uri).toLowerCase().endsWith(".pdf");
    }

    private void handleSongUri(Uri uri) { if (uri == null) return; if (songStatusLabel != null) songStatusLabel.setText("正在读取 " + displayName(uri)); uploadSong(uri); }

    private void downloadSong() {
        String u = urlText.getText().toString().trim(); if (!(u.startsWith("https://") || u.startsWith("http://"))) { toast("请输入 http/https 曲谱地址"); return; }
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE); DownloadManager.Request r = new DownloadManager.Request(Uri.parse(u)); r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); r.setTitle("下载尤克里里曲谱"); downloadId = dm.enqueue(r); toast("已开始下载，完成后自动处理");
    }
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() { @Override public void onReceive(Context c, Intent i) { if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2) == downloadId) { DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE); Uri u = dm.getUriForDownloadedFile(downloadId); if (u != null) handleSongUri(u); else toast("曲谱下载失败"); } } };

    private void uploadSong(Uri uri){
        final String name=displayName(uri);String guessed=getContentResolver().getType(uri);final String initialMime=guessed==null?"application/octet-stream":guessed;
        scoreWorker.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri)){
            byte[] raw=ScoreRepository.read(in);String mime=initialMime;
            if(raw.length>4&&raw[0]=='%'&&raw[1]=='P'&&raw[2]=='D'&&raw[3]=='F')mime="application/pdf";
            if(!mime.startsWith("image/")&&name.toLowerCase().matches(".*\\.(png|jpg|jpeg|webp|heic|bmp)$")){String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase();String detected=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);mime=detected==null?"image/jpeg":detected;}
            ScoreRepository.Item item=scoreRepo.importFile(name,mime,raw);
            if(mime.equals("application/pdf")||mime.startsWith("image/")){main.post(()->{rebuildSavedScores();openScoreDocument(item.id);});return;}
            midiSkipped=0;byte[] normalized=normalizeSong(raw);int skipped=midiSkipped;
            if(normalized!=null)scoreRepo.setEvents(item,normalized,skipped>0?"音域不全 · "+skipped+"个音符超出C4—D#6":"音域完整",skipped);else if(skipped>0){item.outside=skipped;item.status="无可演奏音符";item.warning="音域不全 · "+skipped+"个音符超出C4—D#6";scoreRepo.write(item);}
            main.post(()->{rebuildSavedScores();
                if(PreviewScore.isMidi(raw))MidiOctaveEditor.show(this,scoreRepo,item.id,()->{rebuildSavedScores();selectSavedScore(item.id);});
                else if(normalized!=null)selectSavedScore(item.id);
                else{toast("原文件已保存，暂未读出可练习音符");openScoreDocument(item.id);}});
        }catch(Exception e){main.post(()->toast("导入失败："+e.getMessage()));}});
    }

    private void uploadBuiltinSong() {
        try (InputStream in = getResources().openRawResource(R.raw.demo_scale_txt); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[512]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
            byte[] song = normalizeSong(out.toByteArray()); if (song == null) { toast("内置示例格式错误"); return; } selectImportedSong(song, "内置示例 demo_scale.txt");
        } catch (Exception e) { toast("读取内置示例失败"); }
    }

    private void uploadBuiltinMidi() {
        try {
            byte[] midi = readRawResource(R.raw.demo_scale_midi); byte[] song = normalizeSong(midi); if (song == null) { toast("内置 MIDI 示例格式错误"); return; } selectImportedSong(song, "内置示例 demo_scale.mid");
        } catch (Exception e) { toast("读取内置 MIDI 失败"); }
    }

    private byte[] readRawResource(int id) throws Exception {
        try (InputStream in = getResources().openRawResource(id); ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[512]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); return out.toByteArray(); }
    }

    private void finishSongUpload(){
        if(!uploadTransportDone || !uploadDeviceConfirmed || pendingUploadId==null)return;
        if(uploadAckTimeout!=null)main.removeCallbacks(uploadAckTimeout);
        uploadedSongId=pendingUploadId;
        songStatusLabel.setText(pendingUploadName+" · 设备已确认接收"+(uploadedSongId.equals(selectedSongId)?"":"；所选曲目需另行上传"));
        unlockBadge(ACH_FIRST_UPLOAD);
        refreshPracticeSummary();
        pendingUploadId=null;pendingUploadName=null;updateSongPlayState();
        final String autoplay=fullGuideAfterUploadId;final int autoplayGeneration=guideUploadGeneration;fullGuideAfterUploadId=null;
        final boolean fullscreenRequired=guideAfterUploadRequiresFullscreen;
        if(autoplay!=null&&autoplay.equals(selectedSongId)&&activityVisible)
            main.post(()->{if(autoplayGeneration==guideUploadGeneration&&activityVisible&&(instrumentIntent||!fullscreenRequired||(fullScreenDialog!=null&&fullPractice))&&bleReady&&powerAllowsControls()&&autoplay.equals(selectedSongId)&&canPlaySelectedSong())beginFullCountdown(true);});
    }
    private void receiveSongResult(byte[] d){
        if(d.length==20 && (d[0]&255)==0x91){SongPlaybackState state=SongPlaybackState.parse(d);if(state!=null)applyDeviceSongState(state);return;}
        if(d.length!=6 || (d[0]&255)!=0x90 || (d[1]&255)==0x15)return;
        int code=d[1]&255;
        if(songTempoSupported && (code==0x10||code==0x11||code==0x12||code==0x13))return;
        long value=(d[2]&255L)|((d[3]&255L)<<8)|((d[4]&255L)<<16)|((d[5]&255L)<<24);
        if(code==3 && pendingUploadId!=null && value==uploadSize){uploadDeviceConfirmed=true;finishSongUpload();}
        else if(code>=0xE0){
            if(uploadAckTimeout!=null)main.removeCallbacks(uploadAckTimeout);
            fullGuideAfterUploadId=null;guideUploadGeneration++;pendingUploadId=null;pendingUploadName=null;uploadedSongId=null;songQueue.clear();
            songStatusLabel.setText("曲谱操作失败，请检查文件并重试");updateSongPlayState();
        }else if(code==0x10){if(guideDeviceReference)songGuideModeLabel.setText(songTempoSupported?"设备已启动，等待曲谱时钟确认":"设备指引 · 1×");}
        else if(code==0x11 || code==0x12 || code==0x13){if(pendingSongControlToken!=0&&pendingSongControlOp==0)return;deviceStopRequested=false;activeDeviceSession=0;clearSongControlPending();hardwarePlayingId=null;if(ledPreview!=null)ledPreview.clearGuide();if(guideDeviceReference)stopSongGuide(code==0x11?"设备曲谱已结束":"设备指引已停止");updateNoteLabels();updateSongPlayState();}
    }

    private boolean queueSong(byte[] song, String name) {
        if (song == null || selectedSongId == null) return false; if (song.length > 65535) { toast("曲谱超过 ESP32 单曲 64KB 限制"); return false; }
        try { song=GuideSong.forLights(song); } catch (IllegalArgumentException error) { toast("灯光曲谱生成失败：" + error.getMessage()); return false; }
        if(song.length>65535){toast("灯光曲谱超过设备容量，请分段导入");return false;}
        if (songChar == null || !bleReady) { toast("请先完成 ESP32 连接"); return false; }
        if (!songQueue.isEmpty() || pendingUploadId != null || (songWriteBusy && activeWrite==songChar)) { toast("已有曲谱正在上传，请等待数据发送完成"); return false; }
        if (hasDeviceSongIntent()) stopDeviceSong();
        else if(deviceSongState!=null&&deviceSongState.state!=0){deviceStopRequested=true;if(!writeCommand("SONG_STOP")){toast("无法先停止设备指引，请重试");return false;}}
        cancelPendingSongPlay(); stopSongGuide("曲谱数据发送中 · 跟弹引导已停止");
        uploadedSongId = null; pendingUploadId = selectedSongId; pendingUploadName = name;
        uploadTransportDone=false;uploadDeviceConfirmed=false;uploadSize=song.length;deviceLightTimeline=SongTimeline.parse(song);
        CRC32 crc = new CRC32(); crc.update(song); songQueue.clear();
        byte[] begin = new byte[]{1, (byte) song.length, (byte) (song.length >> 8), (byte) crc.getValue(), (byte) (crc.getValue() >> 8), (byte) (crc.getValue() >> 16), (byte) (crc.getValue() >> 24)}; songQueue.add(begin);
        int off = 0; while (off < song.length) { int sz = Math.min(180, Math.min(negotiatedMtu - 6, song.length - off)); byte[] p = new byte[3 + sz]; p[0] = 2; p[1] = (byte) off; p[2] = (byte) (off >> 8); System.arraycopy(song, off, p, 3, sz); songQueue.add(p); off += sz; }
        songQueue.add(new byte[]{3}); sendNextSongPacket(); String lowerName = name == null ? "" : name.toLowerCase(); String skipped = ((lowerName.endsWith(".mid") || lowerName.endsWith(".midi")) && midiSkipped > 0) ? " · 跳过 " + midiSkipped + " 个超出 G/C/E/A 18 品范围的音符" : ""; if (songStatusLabel != null) songStatusLabel.setText("上传中 · " + name + " · " + rowsInfo(song.length) + skipped); updateSongPlayState(); toast("曲谱数据发送中…"); return true;
    }

    private void openPdf(Uri uri) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW); view.setDataAndType(uri, "application/pdf"); view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(view, "打开曲谱 PDF"));
        } catch (Exception e) { toast("手机没有可用的 PDF 阅读器"); }
    }

    private String rowsInfo(int bytes) { return bytes + " B"; }

    /** 支持 MIDI、UKS1、CSV/TXT/TSV 事件文本和简单音名事件；PDF 仅在含可提取事件文本时自动转换。 */
    private byte[] normalizeSong(byte[] raw) {
        if (raw == null) return null;
        midiSkipped = 0;
        if (raw.length >= 8 && raw[0] == 'U' && raw[1] == 'K' && raw[2] == 'S' && raw[3] == '1') return GuideSong.upgrade(raw);
        try {
            if (raw.length >= 4 && raw[0] == 'M' && raw[1] == 'T' && raw[2] == 'h' && raw[3] == 'd') return normalizeMidiSong(raw);
            String text = raw.length >= 4 && raw[0] == '%' && raw[1] == 'P' && raw[2] == 'D' && raw[3] == 'F'
                    ? extractPdfText(raw) : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            return normalizeTextSong(text);
        } catch (Exception ignored) { return null; }
    }

    private static class MidiEvent { long tick; int note; int velocity; MidiEvent(long t, int n, int v) { tick = t; note = n; velocity = v; } }
    private static class MidiTempo { long tick; int microsPerQuarter; MidiTempo(long t, int u) { tick = t; microsPerQuarter = u; } }

    private byte[] normalizeMidiSong(byte[] raw) {
        try{ScorePitch.Result result=MidiGuide.convert(raw);midiSkipped=result.outside+result.overflow;return result.events;}
        catch(IllegalArgumentException e){return null;}
    }

    private void parseMidiTrack(byte[] raw, int start, int end, ArrayList<MidiEvent> events, ArrayList<MidiTempo> tempos) {
        int[] pos = {start}; long tick = 0; int running = 0;
        while (pos[0] < end) {
            long delta = readMidiVlq(raw, pos, end); if (delta < 0) break; tick += delta; if (pos[0] >= end) break;
            int first = raw[pos[0]] & 255; int status;
            if (first < 0x80) { if (running == 0) break; status = running; } else { status = first; pos[0]++; if (status < 0xF0) running = status; else running = 0; }
            if (status == 0xFF) { int type = readMidiU8(raw, pos, end); long len = readMidiVlq(raw, pos, end); if (type < 0 || len < 0 || pos[0] + len > end) break; if (type == 0x51 && len >= 3) { int u = ((raw[pos[0]] & 255) << 16) | ((raw[pos[0] + 1] & 255) << 8) | (raw[pos[0] + 2] & 255); tempos.add(new MidiTempo(tick, u)); } pos[0] += (int) len; continue; }
            if (status == 0xF0 || status == 0xF7) { long len = readMidiVlq(raw, pos, end); if (len < 0 || pos[0] + len > end) break; pos[0] += (int) len; continue; }
            int kind = status & 0xF0; int n = readMidiU8(raw, pos, end); if (n < 0) break;
            if (kind == 0x90 || kind == 0x80) { int velocity = readMidiU8(raw, pos, end); if (velocity < 0) break; if (kind == 0x90 && velocity > 0 && n < 128) events.add(new MidiEvent(tick, n, velocity)); }
            else if (kind == 0xA0 || kind == 0xB0 || kind == 0xE0) { if (readMidiU8(raw, pos, end) < 0) break; }
            else if (kind != 0xC0 && kind != 0xD0) break;
        }
    }

    private int readMidiU8(byte[] raw, int[] pos, int end) { return pos[0] < end ? raw[pos[0]++] & 255 : -1; }
    private int readMidiU16(byte[] raw, int[] pos, int end) { int a = readMidiU8(raw, pos, end), b = readMidiU8(raw, pos, end); return a < 0 || b < 0 ? -1 : (a << 8) | b; }
    private long readMidiU32(byte[] raw, int[] pos, int end) { long a = readMidiU8(raw, pos, end), b = readMidiU8(raw, pos, end), c = readMidiU8(raw, pos, end), d = readMidiU8(raw, pos, end); return a < 0 || b < 0 || c < 0 || d < 0 ? -1 : (a << 24) | (b << 16) | (c << 8) | d; }
    private long readMidiVlq(byte[] raw, int[] pos, int end) { long value = 0; for (int i = 0; i < 4; ++i) { int b = readMidiU8(raw, pos, end); if (b < 0) return -1; value = (value << 7) | (b & 0x7F); if ((b & 0x80) == 0) return value; } return -1; }

    private String extractPdfText(byte[] raw) {
        String pdf = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1); StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("\\(([^()]*)\\)\\s*T[Jj]").matcher(pdf);
        while (m.find()) { if (out.length() > 0) out.append('\n'); out.append(m.group(1)); }
        return out.toString();
    }

    private byte[] normalizeTextSong(String text) {
        if (text == null) return null; if(GuideEventText.isExact(text)){try{return GuideEventText.parse(text);}catch(Exception invalid){return null;}} ArrayList<byte[]> rows = new ArrayList<>();
        try {
            for (String line : text.replace('\uFEFF', ' ').split("\\r?\\n")) {
                line = line.trim(); int hash = line.indexOf('#'); if (hash >= 0) line = line.substring(0, hash).trim();
                if (line.length() == 0) continue; String[] f = line.split("[,;\\s]+"); if (f.length < 2 || !isIntegerToken(f[0])) continue;
                int delta = number(f[0]); if (delta < 0 || delta > 65535) return null;
                if (f.length >= 3 && isIntegerToken(f[1]) && isIntegerToken(f[2])) {
                    int cols = number(f[1]), row = number(f[2]); int level = f.length > 3 ? number(f[3]) : 180; int flags = f.length > 4 ? number(f[4]) : 0;
                    if (!appendEvent(rows, delta, cols, row, level, flags)) return null; continue;
                }
                if (f.length >= 3 && stringIndex(f[1]) >= 0 && isIntegerToken(f[2])) {
                    int col = stringIndex(f[1]); int fret = number(f[2]); int level = f.length > 3 ? number(f[3]) : 180; int flags = f.length > 4 ? number(f[4]) : 0;
                    if (!appendEvent(rows, delta, 1 << col, fret, level, flags)) return null; continue;
                }
                int level = f.length >= 3 && isIntegerToken(f[2]) ? number(f[2]) : 180; int flags = f.length >= 4 && isIntegerToken(f[3]) ? number(f[3]) : 0;
                boolean any = false; String[] notes = f[1].split("[/+|&]");
                ArrayList<ScorePitch.Note> chord=new ArrayList<>();
                for(String token:notes){int midi=noteMidi(token);if(midi<0)return null;chord.add(new ScorePitch.Note(0,midi));}
                ScorePitch.Result mapped=ScorePitch.convert(chord,0);if(mapped.outside>0||mapped.overflow>0)return null;
                for(SongTimeline.Event event:SongTimeline.parse(mapped.events).events)if(event.level>0&&event.mask!=0){if(!appendEvent(rows,any?0:delta,event.mask,event.fret,level,flags))return null;any=true;}
                if (!any) return null;
            }
            byte[] result=encodeRows(rows);
            return result;
        } catch (Exception ignored) { return null; }
    }

    private boolean appendEvent(ArrayList<byte[]> rows, int delta, int cols, int row, int level, int flags) {
        if (delta < 0 || delta > 65535 || cols < 0 || cols > 15 || row < 0 || row > 18 || level < 0 || level > 255 || flags < 0 || flags > 255) return false;
        rows.add(new byte[]{(byte) delta, (byte) (delta >> 8), (byte) cols, (byte) row, (byte) level, (byte) flags}); return true;
    }

    private boolean appendTimedEvent(ArrayList<byte[]> rows, long deltaMs, int cols, int row, int level, int flags) {
        if (deltaMs < 0) return false; while (deltaMs > 65535) { if (!appendEvent(rows, 65535, 0, 0, 0, 0)) return false; deltaMs -= 65535; }
        return appendEvent(rows, (int) deltaMs, cols, row, level, flags);
    }

    private byte[] encodeRows(ArrayList<byte[]> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 10922) return null; ByteArrayOutputStream out = new ByteArrayOutputStream(8 + rows.size() * 6);
        try { out.write('U'); out.write('K'); out.write('S'); out.write('1'); out.write(1); out.write(0); out.write(rows.size() & 255); out.write((rows.size() >> 8) & 255); for (byte[] r : rows) out.write(r); return out.toByteArray(); } catch (Exception e) { return null; }
    }

    private boolean isIntegerToken(String s) { try { number(s); return true; } catch (Exception e) { return false; } }
    private int number(String s) {
        s = s.trim().toLowerCase();
        if (s.startsWith("0x")) return Integer.parseInt(s.substring(2), 16);
        return Integer.parseInt(s);
    }
    private void sendNextSongPacket() {
        if (songWriteBusy || gatt == null || !bleReady) return;
        // 控制队列优先；失效/未上传完的 SONG_PLAY 必须丢弃，不能抢占曲谱上传。
        while (isSongPlayCommand(commandQueue.peek()) &&
                (queuedPlayId == null || !queuedPlayId.equals(selectedSongId) || !canPlaySelectedSong())) {
            cancelPendingSongPlay();
        }
        final boolean reading=READ_SETTINGS.equals(commandQueue.peek());
        if(reading && (settingsChar==null || (settingsChar.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)==0)){
            commandQueue.poll();main.post(this::sendNextSongPacket);return;
        }
        if(reading)activeWrite=settingsChar;
        else if (!commandQueue.isEmpty()) {
            activeWrite = commandChar;
            String command = commandQueue.peek();
            if (isSongPlayCommand(command)) { activePlayToken = queuedPlayToken; activePlayId = queuedPlayId; }
            else { activePlayToken = 0; activePlayId = null; }
            activeWrite.setValue(command.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        else if (!songQueue.isEmpty()) { activeWrite = songChar; activeWrite.setValue(songQueue.peek()); }
        else return;
        activeWrite.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        try {
            songWriteBusy = reading?gatt.readCharacteristic(activeWrite):gatt.writeCharacteristic(activeWrite);
            // 注意：这里**不**重新计时看门狗。看门狗只以「最近一次成功写完」为基准，
            // 否则每个包发起时都会重置窗口，等价于「每包独立 deadline」，
            // 上传 N 包时失败率会被放大成 1-(1-p)^N。若每包之间超时，
            // 说明链路确实没有进展，就该让看门狗按计划触发。
            if (!songWriteBusy) { activeWrite = null; if (++writeStartRetries > 3) { failBle("发送未能开始，请重新连接"); return; } main.postDelayed(this::sendNextSongPacket, 200); }
            else {
                // 首包（尚无看门狗在跑）时起表；之后由完成回调续期。
                if (!writeWatchdogArmed) armWriteProgressTimeoutFor(gatt);
            }
        } catch (SecurityException e) { failBle("没有蓝牙写入权限"); }
    }

    private void armWriteProgressTimeout() { armWriteProgressTimeoutFor(gatt); }

    /**
     * 写入进度看门狗：以「最近一次成功写完」为基准计时。
     * 「队列里还有待发数据」且「连续 {@link #WRITE_STALL_TIMEOUT_MS} 内没有任何一包写完」时，
     * 判定该写入已卡死并断开重连（由自动重连兜底恢复）。
     *
     * 曾尝试在这里「先重发同一包、重发仍无进展再断开」，以降低长上传的失败率
     * （单包超时概率 p、N 包时拆链路的失败率为 1-(1-p)^N）。该方案已撤销：
     * `onCharacteristicWrite` 不携带任何标识，无法判断回调属于哪一次写入；
     * 迟到的回调会 poll 掉「正在重发的那一包」，使队列与真实进度错开一位、
     * 上传提前判定完成，甚至漏发分片。在没有真机验证手段的前提下，
     * 引入这种不可验证的错位风险比保留「偶发超时就重连」更危险。
     * 长上传失败率被包数放大这一点仍未解决，需要真机 + 抓包定位后另做方案。
     */
    private void armWriteProgressTimeoutFor(final BluetoothGatt expected) {
        if (expected == null) return;
        if (commandQueue.isEmpty() && songQueue.isEmpty()) return;   // 没有待发数据就不需要看门狗
        if (writeTimeout != null) main.removeCallbacks(writeTimeout);
        final long generation = ++writeGeneration;
        writeWatchdogArmed = true;
        writeTimeout = () -> {
            if (gatt != expected || !songWriteBusy || writeGeneration != generation) return;
            writeWatchdogArmed = false;
            failBle("发送超时，请重新连接");
        };
        main.postDelayed(writeTimeout, WRITE_STALL_TIMEOUT_MS);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    @Override protected void onStart() {
        super.onStart();
        activityVisible = true;main.removeCallbacks(scoreHealthTick);main.post(scoreHealthTick);
        // 启动动画期间退到后台会按设计 cancel（不再回调）；回前台时清掉这层透明覆盖层，
        // 并把被延后的首启欢迎弹窗补上，避免出现「透明层残留 + 欢迎弹窗永远不出现」。
        if (splashOverlay != null && !splashOverlay.isRunning()) {
            SplashOverlay stale = splashOverlay;
            splashOverlay = null;
            if (appShell != null) appShell.removeView(stale);
            applyWindowTheme();
            Runnable pending = pendingAfterSplash;
            pendingAfterSplash = null;
            if (pending != null) main.post(pending);
        }
        if(gatt!=null&&bleReady)try{gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);}catch(SecurityException ignored){}
        requestAnalysisMode();
        updateSongPlayState();
        setLedAnimation(true);
        // 回前台时若自动重连开着且仍未连接，补一次重连计划：
        // 后台期间待定的重连会被丢弃（activityVisible=false 时直接 return），不补的话
        // 应用会一直断着，直到用户手动点“扫描并连接”。次数上限在 scheduleAutoReconnect 内部。
        if (gatt == null && !scanning && settings != null && settings.autoConnect()) scheduleAutoReconnect(false);
    }

    @Override protected void onStop() {
        if(performanceTake!=null && performanceTake.active()){performanceTake.stop();updateTakeUi();}
        aiStopCandidatePreview();
        if(practiceGame!=null){practiceGame.close();practiceGame=null;}
        stopScoreAudition();
        cancelFullCountdown();
        // 手机侧音频玩法在退到后台时必须停：节拍器/和弦试听不属于设备状态，但后台继续响会吓到用户。
        stopMetronome();
        if (chordPlayer != null) chordPlayer.stop();
        activityVisible = false;fullGuideAfterUploadId=null;guideUploadGeneration++;main.removeCallbacks(scoreHealthTick);
        if(gatt!=null)try{gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);}catch(SecurityException ignored){}
        boolean hadDevicePlay = hasDeviceSongIntent();
        cancelPendingSongPlay(); stopSongGuide("应用已进入后台，本地引导已停止");
        if (hadDevicePlay && bleReady) { writeCommand("SONG_STOP"); songGuideModeLabel.setText("应用进入后台 · 停止指令已排队，设备状态待确认"); }
        requestAnalysisMode();
        updateSongPlayState(); setLedAnimation(false); super.onStop();
    }
    @Override protected void onDestroy() { stopPaintingDemo();if(jamDialog!=null)jamDialog.dismiss();if(envelopeDialog!=null)envelopeDialog.dismiss();if(paintToExport!=null){paintToExport.recycle();paintToExport=null;} aiPreviewObserver=null;aiPreviewSession.stop();pendingAiDocumentCallback=null;pendingBrowserScoreSelected=null; if(practiceGame!=null)practiceGame.close(); dismissSplash(); if(cachedAiClient!=null){cachedAiClient.shutdown();cachedAiClient=null;} if(auditionPlayer!=null)auditionPlayer.close(); if(aiCandidatePreview!=null){aiCandidatePreview.close();aiCandidatePreview=null;} if(aiCandidatePreviewStop!=null){main.removeCallbacks(aiCandidatePreviewStop);aiCandidatePreviewStop=null;} if(metronome!=null)metronome.release(); if(chordPlayer!=null)chordPlayer.release(); unregisterReceiver(downloadReceiver); if (scanning && adapter != null) { try { adapter.getBluetoothLeScanner().stopScan(scanCallback); } catch (Exception ignored) {} } scanning = false; disconnect(); if (settings != null && settingsListener != null) settings.removeListener(settingsListener); if (feedback != null) feedback.release(); main.removeCallbacksAndMessages(null);scoreWorker.shutdown();audioWorker.shutdownNow();super.onDestroy(); }

    /** 打开独立的「我的曲库」页面（搜索、排序、重命名、导出、回收站都在那里）。 */
    private void openScoreLibrary(){
        Intent i=new Intent(this,ScoreLibraryActivity.class);
        startActivityForResult(i,REQ_SCORE_LIBRARY);
    }

    private void rebuildSavedScores(){
        if(savedScores==null)return;
        savedScores.removeAllViews();
        java.util.List<ScoreRepository.Item> items=scoreRepo.list(false);
        if(items.isEmpty()){
            savedScores.addView(kit.text("还没有曲谱。导入照片、PDF 或 MIDI 后会保存在「我的曲库」里。",ts(13),cTextSecondary()));
            return;
        }
        // 本页只作预览：最多列最近 3 首，避免导入越多曲谱页越长（完整管理在「我的曲库」页）。
        int shown=0;
        for(ScoreRepository.Item item:items){
            if(!libraryQuery.isEmpty()&&FuzzySongSearch.score(item.title,item.difficulty,libraryQuery)==0)continue;
            if(libraryFilter>=1&&libraryFilter<=3&&!item.difficulty.equals(libraryFilter==1?"入门":libraryFilter==2?"进阶":"挑战"))continue;
            if(shown>=3)break;
            savedScores.addView(savedScoreRow(item));
            shown++;
        }
        if(items.size()>shown){
            TextView all=outlineAction("查看全部 "+items.size()+" 首");
            all.setOnClickListener(v->openScoreLibrary());
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.topMargin=dp(4);
            savedScores.addView(all,lp);
        }
    }

    /**
     * 「我的曲库」单行。
     *
     * 原来每首曲谱是一张卡片，标题 + 状态 + **整行「管理曲谱」按钮**（12dp 上边距 + 12dp 下边距），
     * 一屏放不下两三首，导入多了就无限往下延伸。现在改为紧凑单行：
     * 标题（可换行）+ 一行元信息，右侧一个「⋮」入口；整行可点即开始练习，长按或点 ⋮ 打开管理菜单。
     */
    private LinearLayout savedScoreRow(final ScoreRepository.Item item){
        LinearLayout outer=column();
        LinearLayout body=row();
        body.setPadding(dp(14),dp(12),dp(10),dp(12));
        body.setBackground(kit.card());
        body.setClickable(true);body.setFocusable(true);

        LinearLayout texts=column();
        TextView title=kit.textBold(item.title,ts(15),cTextPrimary());
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);
        String meta=item.difficulty+" · "+item.status+(item.outside>0?" · 音域不全":"");
        TextView sub=kit.text(meta,ts(12),item.outside>0?cDanger():cTextSecondary());
        sub.setPadding(0,dp(3),0,0);
        texts.addView(sub);
        body.addView(texts,new LinearLayout.LayoutParams(0,-2,1));

        TextView menu=kit.textBold("⋮",ts(20),cTextTertiary());
        menu.setGravity(Gravity.CENTER);
        menu.setMinWidth(dp(40));menu.setMinimumHeight(dp(40));
        menu.setClickable(true);menu.setFocusable(true);
        menu.setBackground(kit.ripple(cSurface2(),12));
        menu.setOnClickListener(v->{tap();scoreMenu(item);});
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-2,-2);
        mp.leftMargin=dp(8);
        body.addView(menu,mp);

        body.setOnClickListener(v->selectSavedScore(item.id));
        body.setOnLongClickListener(v->{scoreMenu(item);return true;});
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=dp(8);
        outer.addView(body,lp);
        return outer;
    }
    private long savedScoreRequest;
    private void selectSavedScore(String id){selectSavedScore(id,null);}
    private void selectSavedScore(String id,Runnable whenReady){
        final long request=++savedScoreRequest;
        scoreWorker.execute(()->{try{
            ScoreRepository.Item item=scoreRepo.get(id);if(item.deleted!=0)throw new Exception("曲谱已在回收站");
            byte[] events;try{events=scoreRepo.events(id);}catch(Exception e){main.post(()->{if(!isDestroyed()&&request==savedScoreRequest)openScoreDocument(id);});return;}
            if(events.length>=8&&events[4]==1&&scoreRepo.source(id).isFile()){
                boolean midi;try(java.io.FileInputStream in=new java.io.FileInputStream(scoreRepo.source(id))){byte[] h=new byte[4];midi=in.read(h)==4&&PreviewScore.isMidi(h);}
                if(midi){ScorePitch.Result result=MidiGuide.convert(ScoreRepository.readAtomic(scoreRepo.source(id)),item.midiTranspose,item.midiBalanced);events=result.events;scoreRepo.setEvents(item,events,result.warning(),result.outside+result.overflow);item=scoreRepo.get(id);}
            }
            final byte[] ready=GuideSong.upgrade(events);final SongTimeline timeline=SongTimeline.parse(ready);final ScoreRepository.Item selected=item;
            main.post(()->{if(isDestroyed()||request!=savedScoreRequest)return;
                if(timeline.noteCount==0){toast("音域没有可用音符，原谱已保留，请校对或移调");openScoreDocument(id);return;}
                selectSong("saved:"+id,selected.title,ready,timeline,selected.difficulty+" · "+formatSongTime(timeline.durationMs)+" · "+selected.warning,true);
                rememberSelectedSong("saved:"+id,selected.title,SettingsStore.KIND_IMPORT,ready);showSongSection(true);
                if(whenReady!=null)whenReady.run();
            });
        }catch(Exception e){main.post(()->{if(!isDestroyed()&&request==savedScoreRequest)toast("曲谱不可用："+e.getMessage());});}});
    }
    private void openScoreDocument(String id){Intent i=new Intent(this,ScoreDocumentActivity.class);i.putExtra("id",id);startActivityForResult(i,REQ_SCORE_VIEW);}
    private void scoreMenu(final ScoreRepository.Item item){
        final java.util.List<AppDialogs.Item> actions=new ArrayList<>();
        actions.add(new AppDialogs.Item("开始练习","载入这首曲谱并进入练习",false));
        actions.add(new AppDialogs.Item("查看原谱 / 校对","查看原件、离线识谱与校对文稿",false));
        actions.add(new AppDialogs.Item("重命名","修改曲谱显示名称",false));
        actions.add(new AppDialogs.Item("导出原谱","把原件另存到手机存储",false));
        actions.add(new AppDialogs.Item("移到回收站","原件与练习数据保留，可随时恢复",true));
        if(MidiOctaveEditor.supports(item))actions.add(new AppDialogs.Item("整体升降八度",MidiOctaveEditor.label(item.midiTranspose),false));
        String subtitle=item.difficulty+" · "+item.status+(item.warning==null||item.warning.isEmpty()?"":" · "+item.warning);
        AppDialogs.list(this,item.title,actions,new AppDialogs.OnPick(){
            @Override public void picked(int which){
                if(which==0)selectSavedScore(item.id);
                else if(which==1)openScoreDocument(item.id);
                else if(which==2)renameScore(item);
                else if(which==3)exportScore(item);
                else if(which==5)MidiOctaveEditor.show(MainActivity.this,scoreRepo,item.id,()->{rebuildSavedScores();selectSavedScore(item.id);});
                else trashScore(item);
            }
        },null,null,null,null);
    }

    /** 重命名：自定义输入框（跟随主题与字号），不再用系统默认 EditText 对话框。 */
    private void renameScore(final ScoreRepository.Item item){
        AppDialogs.prompt(this,"重命名曲谱",item.title,new AppDialogs.OnText(){
            @Override public void onText(String value){
                try{scoreRepo.rename(item.id,value);rebuildSavedScores();toast("已重命名");}
                catch(Exception e){toast(e.getMessage()==null?"重命名失败":e.getMessage());}
            }
        });
    }

    /** 移到回收站：若正在练习这首，先干净地停下。 */
    private void trashScore(final ScoreRepository.Item item){
        AppDialogs.message(this,"移到回收站？","“"+item.title+"”的原件与练习数据会保留，可随时从回收站恢复。",
            "移到回收站",new android.content.DialogInterface.OnClickListener(){
                @Override public void onClick(android.content.DialogInterface d,int w){
                    try{
                        scoreRepo.trash(item.id);
                        if(item.id.equals(selectedSavedId)){
                            auditionPracticeMode=false;stopScoreAudition();
                            if(hasDeviceSongIntent())stopDeviceSong();
                            stopSongGuide("曲谱已移到回收站");
                            cancelPendingSongPlay();songQueue.clear();
                            pendingUploadId=null;uploadedSongId=null;selectedSongId=null;selectedSavedId=null;
                            selectedSongBytes=null;selectedTimeline=null;songGuideView.setTimeline(null);
                            selectedSongLabel.setText("请选择曲谱");
                            settings.setLastSongId("");settings.setLastSongKind(SettingsStore.KIND_NONE);
                            updateSongPlayState();
                        }
                        rebuildSavedScores();toast("已移到回收站，可恢复");
                    }catch(Exception e){toast("删除失败");}
                }
            },"取消",null,null,null);
    }

    /** 导出原谱到用户选择的位置。 */
    private void exportScore(final ScoreRepository.Item item){
        exportScoreId=item.id;
        Intent out=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        out.setType(item.mime);out.putExtra(Intent.EXTRA_TITLE,item.title);
        out.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(out,REQ_SCORE_EXPORT);
    }
    private void showRecycleBin(){
        final java.util.List<ScoreRepository.Item> items=scoreRepo.list(true);
        if(items.isEmpty()){
            AppDialogs.message(this,"回收站","回收站是空的。\n\n移到回收站的曲谱会保留原件和练习数据，可随时恢复。\n\n曲谱存放在应用内部存储，卸载或清除应用数据会一并删除，重要原谱请用「导出原谱」另存一份。");
            return;
        }
        // 用 AppDialogs 而不是系统默认列表对话框：长文件名会在默认列表里折成好几行、
        // 挤成一大片；这里改成「标题 + 副标题」的卡片行，并可滚动。
        final java.util.List<AppDialogs.Item> rows=new ArrayList<>();
        for(ScoreRepository.Item item:items){
            String when=item.deleted>0?new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.ROOT).format(new java.util.Date(item.deleted)):"";
            rows.add(new AppDialogs.Item(item.title, scoreKindLabel(item)+" · 删除于 "+when, false));
        }
        AppDialogs.list(this,"回收站 · "+items.size()+" 首",rows,new AppDialogs.OnPick(){
            @Override public void picked(int index){trashItemMenu(items.get(index));}
        },"关闭",null,"回收站设置",new android.content.DialogInterface.OnClickListener(){
            @Override public void onClick(android.content.DialogInterface d,int w){chooseTrashRetention();}
        });
    }

    /** 回收站/曲库列表里的一行副标题：谱型 + 状态。 */
    private String scoreKindLabel(ScoreRepository.Item item){
        StringBuilder sb=new StringBuilder();
        if(item.type!=null&&!item.type.isEmpty())sb.append(item.type);
        if(item.mime!=null){
            String mime=item.mime.toLowerCase(java.util.Locale.ROOT);
            String kind=mime.contains("pdf")?"PDF":mime.startsWith("image/")?"图片":mime.contains("midi")?"MIDI":"曲谱";
            if(sb.length()>0)sb.append(" · ");sb.append(kind);
        }
        if(sb.length()==0)sb.append("曲谱");
        return sb.toString();
    }

    /** 回收站单条：恢复 / 永久删除。 */
    private void trashItemMenu(final ScoreRepository.Item item){
        final java.util.List<AppDialogs.Item> actions=new ArrayList<>();
        actions.add(new AppDialogs.Item("恢复到曲库","放回「我的曲库」，练习数据保留",false));
        actions.add(new AppDialogs.Item("永久删除","删除原件和练习数据，无法恢复",true));
        AppDialogs.list(this,item.title,actions,new AppDialogs.OnPick(){
            @Override public void picked(int index){
                if(index==0){
                    try{scoreRepo.restore(item.id);rebuildSavedScores();toast("已恢复到曲库");}
                    catch(Exception e){toast("恢复失败");}
                }else{
                    AppDialogs.message(MainActivity.this,"永久删除原谱？","这将删除原件和练习数据，无法恢复。",
                        "永久删除",new android.content.DialogInterface.OnClickListener(){
                            @Override public void onClick(android.content.DialogInterface d,int w){
                                try{scoreRepo.purge(item.id);toast("已永久删除");}catch(Exception e){toast("删除失败");}
                            }
                        },"取消",null,null,null);
                }
            }
        },"返回",null,null,null);
    }

    /** 回收站保留期选择。 */
    private void chooseTrashRetention(){
        final String[] labels={"永久保留（默认）","30 天","90 天"};
        final String[] notes={"仅手动永久删除，不会自动清理","进入回收站超过 30 天自动永久删除","进入回收站超过 90 天自动永久删除"};
        final int current=getSharedPreferences("score_library",0).getInt("retention_days",0);
        final int currentIndex=current==30?1:current==90?2:0;
        final java.util.List<AppDialogs.Item> rows=new ArrayList<>();
        for(int i=0;i<labels.length;i++){
            rows.add(new AppDialogs.Item(labels[i],notes[i],false,i==currentIndex?"当前":null));
        }
        AppDialogs.list(this,"回收站保留期",rows,new AppDialogs.OnPick(){
            @Override public void picked(int index){
                final int days=index==1?30:index==2?90:0;
                AppDialogs.message(MainActivity.this,"确认保留策略",
                    days==0?"回收站仅手动永久删除。":"进入回收站超过 "+days+" 天的曲谱将永久删除，不可恢复。",
                    "启用",new android.content.DialogInterface.OnClickListener(){
                        @Override public void onClick(android.content.DialogInterface d,int w){
                            getSharedPreferences("score_library",0).edit().putInt("retention_days",days).apply();
                            applyTrashRetention();
                        }
                    },"取消",null,null,null);
            }
        },"返回",null,null,null);
    }
    private static boolean isSongPlayCommand(String command){return (command!=null&&command.startsWith("SP1:5,"))||"SONG_PLAY".equals(command)||(command!=null&&command.startsWith("SP1:0,"));}
    private void clearSongControlPending(){pendingSongControlToken=0;if(songControlTimeout!=null){main.removeCallbacks(songControlTimeout);songControlTimeout=null;}updatePracticeSpeedControls();}
    private void applyGuideRate(double rate){
        if(guideSpeed==rate){updatePracticeSpeedControls();return;}
        abortScoring("倍速已改变，本轮不计入最高分");practiceScorer=null;scoreGeneration++;scoreHint="速度已调整，请重新开始评分";
        guideElapsedBase=currentPracticeElapsed();guideStartedAt=SystemClock.uptimeMillis();guideSpeed=rate;
        getSharedPreferences("practice_clock",0).edit().putInt("speed",(int)Math.round(rate*1000)).apply();loadBestScore();updatePracticeSpeedControls();
    }
    private boolean requestSongControl(int op,int rate){
        if(pendingSongControlToken!=0)return false;
        int token=(++songControlSequence)&65535;if(token==0)token=(++songControlSequence)&65535;
        pendingSongControlToken=token;pendingSongControlOp=op;
        pendingSongControlLabel=op==0?"开始指引":op==1?"调整倍速":op==2?"暂停":"继续";
        if(op==0){queuedPlayToken=token;pendingStartInstrument=instrumentIntent;}
        if(!writeCommand((op==0&&instrumentIntent?"SP1:5,"+rate+","+token:SongPlaybackState.command(op,rate,token)))){clearSongControlPending();return false;}
        songGuideModeLabel.setText(pendingSongControlLabel+" · 等待设备确认");updatePracticeSpeedControls();
        final int expected=token;songControlTimeout=()->{
            if(pendingSongControlToken!=expected)return;boolean start=pendingSongControlOp==0;
            clearSongControlPending();songGuideModeLabel.setText("设备操作确认超时，请检查连接");toast("操作确认超时，正在重新核对设备状态");
            if(start){cancelPendingSongPlay();deviceStopRequested=true;writeCommand("SONG_STOP");}else writeCommand("SONG_INFO");
            updateSongPlayState();
        };main.postDelayed(songControlTimeout,3000);return true;
    }
    private void applyDeviceSongState(SongPlaybackState state){
        songTempoSupported=true;
        if(deviceSongState!=null&&!SongPlaybackState.newer(state.revision,deviceSongState.revision))return;
        deviceSongState=state;

        boolean reply=pendingSongControlToken!=0&&state.token==pendingSongControlToken&&state.op==pendingSongControlOp;
        boolean starting=pendingSongControlToken!=0&&pendingSongControlOp==0;
        if(starting&&!reply)return;
        if(reply){
            int op=pendingSongControlOp;clearSongControlPending();
            if(state.result!=0){
                if(op==0){instrumentIntent=false;pendingStartInstrument=false;queuedPlayId=null;updateSongPlayState();}
                songGuideModeLabel.setText(state.result==1?"设备曲目未播放，操作未生效":state.result==3?"设备曲谱不可用，请重新上传":"设备拒绝了无效曲谱操作");return;
            }
            if(op==0){
                if(state.state==1&&state.instrument!=pendingStartInstrument){
                    stopDeviceSong();songGuideModeLabel.setText("乐器返回了错误的播放模式，请重试");return;
                }
                if(state.state!=1 || queuedPlayId==null || !queuedPlayId.equals(selectedSongId)){queuedPlayId=null;updateSongPlayState();return;}
                hardwarePlayingId=queuedPlayId;queuedPlayId=null;activeDeviceSession=state.session;deviceStopRequested=false;
                instrumentIntent=state.instrument;
                applyGuideRate(state.rate/1000d);startSongGuide(true);
                if(!state.instrument)startScoringSession(state.positionMs);
            }
        }
        if(state.result!=0)return;
        if(state.state==0 && !starting){instrumentIntent=false;pendingStartInstrument=false;}
        if(deviceStopRequested&&state.state!=0)return;
        if(state.state==0 && (state.session==activeDeviceSession||deviceStopRequested)){
            deviceStopRequested=false;activeDeviceSession=0;hardwarePlayingId=null;clearSongControlPending();
            if(ledPreview!=null)ledPreview.clearGuide();
            if(guideDeviceReference){
                boolean ended=deviceLightTimeline!=null&&state.emitted==deviceLightTimeline.events.size();
                pausingPractice=ended;stopSongGuide(ended?"设备曲谱已结束":"设备指引已停止");pausingPractice=false;
            }
            updateSongPlayState();return;
        }
        if(hardwarePlayingId==null||!hardwarePlayingId.equals(selectedSongId))return;
        if(activeDeviceSession==0)activeDeviceSession=state.session; // legacy 1x start on a new firmware
        if(state.session!=activeDeviceSession)return;
        if(deviceLightTimeline!=null&&state.emitted>0&&state.emitted<=deviceLightTimeline.events.size()){
            if(polyGuideFrame!=null && polyGuideFrame.session==state.session){
                if(ledPreview!=null)ledPreview.setGuideLevels(polyGuideFrame.frets,polyGuideFrame.levels);
                if(songGuideView!=null)songGuideView.setDeviceFrets(polyGuideFrame.frets,polyGuideFrame.levels);
            }else{
                SongTimeline.Step step=deviceLightTimeline.currentStepAt(state.positionMs);int[] frets={31,31,31,31};int level=0;
                if(step!=null)for(SongTimeline.Event event:step.events)if(event.level>0){level=Math.max(level,event.level);for(int c=0;c<4;c++)if((event.mask&(1<<c))!=0)frets[c]=frets[c]==31?event.fret:Math.max(frets[c],event.fret);}
                if(ledPreview!=null)ledPreview.setGuideFrets(frets,level);
                if(songGuideView!=null)songGuideView.setDeviceFrets(frets,level);
            }
        }
        applyGuideRate(state.rate/1000d);guideDeviceReference=true;guideElapsedBase=state.positionMs;guideStartedAt=SystemClock.uptimeMillis();guideRunning=state.state==1;
        if(scoringActive()){
            scoreElapsedBase=(long)(state.positionMs/scoreRate);scoreStartedAt=SystemClock.uptimeMillis();scorePaused=state.state==2;
        }
        main.removeCallbacks(guideTick);if(guideRunning)main.post(guideTick);else if(songGuideView!=null)songGuideView.setElapsed(state.positionMs);
        if(pendingSongControlToken==0)songGuideModeLabel.setText((state.instrument?"乐器播放 · ":"设备指引 · ")+guideSpeed+"×"+(state.state==2?" · 已暂停":""));
    }
    private void renderGuideProgress(long elapsed,long duration,double rate){
            songGuideView.setElapsed(elapsed);
            songProgressLabel.setText(formatSongTime(elapsed) + " / " + formatSongTime(duration) + " · "+rate+"×");
    }
    private long currentPracticeElapsed(){if(auditionPlayer!=null&&auditionPlayer.isActive())return auditionPlayer.positionMs();return (long)(guideElapsedBase+(guideRunning?(SystemClock.uptimeMillis()-guideStartedAt)*(guideSpeed):0));}
    private void choosePracticeSpeed(){
        final java.util.List<AppDialogs.Item> rows=new ArrayList<>();
        for(int i=1;i<=8;i++)rows.add(new AppDialogs.Item(rateText(i*.25),"",false,guideSpeed==i*.25?"当前":null));
        AppDialogs.list(this,"练习倍速",rows,n->setPracticeSpeed((n+1)*.25),null,null,null,null);
    }
    private void pausePractice(){
        if(auditionPlayer!=null&&auditionPlayer.isActive()){auditionPlayer.togglePause();return;}
        if(fullCountdown.active()){cancelFullCountdown();songGuideModeLabel.setText("准备已取消");return;}
        if(auditionPracticeMode){playScoreAudition(auditionJumpToPractice);return;}
        if(deviceStopRequested){toast("等待设备停止确认");return;}
        if(guideDeviceReference && hardwarePlayingId!=null){
            if(!songTempoSupported){toast("设备暂停/继续需要新版 ESP32 固件");return;}
            if(pendingSongControlToken!=0){toast("等待设备确认上一操作");return;}
            requestSongControl(deviceSongState!=null&&deviceSongState.state==2?3:2,(int)Math.round(guideSpeed*1000));return;
        }
        if (guideRunning) {
            guideElapsedBase=currentPracticeElapsed();
            if(scoringActive()){scoreElapsedBase=scoringElapsed();scorePaused=true;}
            localGuidePaused = true;
            pausingPractice=true;stopSongGuide("已暂停");pausingPractice=false;
            if (fullPractice && fullScreenDialog != null) songGuideView.setFeedback("已暂停", cAccent());
        } else if(selectedTimeline!=null){
            if(!localGuidePaused&&(guideElapsedBase<=0||guideElapsedBase>=selectedTimeline.durationMs)){requestLocalPractice();return;}
            if(scoringActive()){if(!canScoreNow()){abortScoring("恢复时设备反馈不可用");toast("请连接设备后重新开始评分");return;}scoreStartedAt=SystemClock.uptimeMillis();scorePaused=false;}
            localGuidePaused = false;
            if (fullPractice && fullScreenDialog != null) songGuideView.setFeedback("", cAccent());
            guideRunning=true;guideStartedAt=SystemClock.uptimeMillis();songGuideModeLabel.setText(scoringActive()?"评分练习 · "+scoreRate+"×":"本地练习 · "+guideSpeed+"×");if(bleReady)main.post(practiceLease);main.post(guideTick);
        }updateScoringUi();
    }
    private String guideDirectionText(boolean compact){
        boolean right=settings.guideLandingRight();
        return compact?(right?"右侧基准":"左侧基准"):(right?"基准线：右侧 · 音符向右":"基准线：左侧 · 音符向左");
    }
    private void toggleGuideDirection(){tap();settings.setGuideLandingRight(!settings.guideLandingRight());updateGuideDirection();}
    private void updateGuideDirection(){
        if(songGuideView!=null){songGuideView.setLandingRight(settings.guideLandingRight());songGuideView.setStringsReversed(settings.guideStringsReversed());}
        if(guideDirectionButton!=null)guideDirectionButton.setText(guideDirectionText(true));
        if(guideStringOrderButton!=null)guideStringOrderButton.setText(stringOrderText());
        if(fullDirectionButton!=null)fullDirectionButton.setText(guideDirectionText(true));
        if(fullStringOrderButton!=null)fullStringOrderButton.setText(settings.guideStringsReversed()?"弦序 4→1":"弦序 1→4");
    }
    private boolean capoRangePlayable(){
        int n=boardCapo();if(n==0||selectedTimeline==null)return true;
        for(SongTimeline.Event e:selectedTimeline.events)if(e.level>0&&e.mask!=0&&CapoMapping.physical(e.fret,n)==31){
            AppDialogs.message(this,"当前夹位超出曲谱范围","第"+n+"品变调夹后，这首曲子有指法超过18品。请降低夹位或在曲谱校对中调整指法。","知道了",null,null,null,null,null);return false;
        }return true;
    }
    private void startInstrumentPlayback(){
        if(capoState==null || capoExpired || !capoState.fresh() || (capoState.features&4)==0){toast("请更新配套FPGA与ESP32后使用乐器播放");return;}
        startFullScreenGuide(true);
    }
    private void startGuidedPractice(){startFullScreenGuide(false);}
    private void startFullScreenGuide(){startFullScreenGuide(instrumentIntent);}
    private void startFullScreenGuide(boolean instrument){
        if(!bleReady){toast("请先连接乐器");return;}
        if(!powerAllowsControls()){toast("请先唤醒乐器");return;}
        if(selectedSongBytes==null||selectedSongId==null){toast("请先选择曲谱");return;}
        if(!capoRangePlayable())return;
        if(pendingSongControlToken!=0||pendingUploadId!=null||!songQueue.isEmpty()){toast("正在处理上一操作，请稍候");return;}
        instrumentIntent=instrument;
        if(instrument)abortScoring("乐器自动播放不计入练习成绩");
        if(canPlaySelectedSong()){beginFullCountdown(true);return;}
        if(selectedSavedId!=null)try{ScoreRepository.Item item=scoreRepo.get(selectedSavedId);if(item.outside>0){
            AppDialogs.message(this,"曲谱音域不完整",item.warning+"。是否同步可演奏部分并开始指引？","继续",(d,w)->uploadAndStartFullScreenGuide(),"返回",null,null,null);return;
        }}catch(Exception e){toast("曲目不可用");return;}
        uploadAndStartFullScreenGuide();
    }
    private void uploadAndStartFullScreenGuide(){
        if(hasDeviceSongIntent()||(deviceSongState!=null&&deviceSongState.state!=0))stopDeviceSong();
        else stopSongGuide("正在同步曲谱，完成后开始灯光指引");
        cancelFullCountdown();stopScoreAudition();
        guideAfterUploadRequiresFullscreen=fullScreenDialog!=null&&fullPractice;
        guideUploadGeneration++;fullGuideAfterUploadId=selectedSongId;
        if(!queueSong(selectedSongBytes,selectedSongName)){fullGuideAfterUploadId=null;guideUploadGeneration++;return;}
        restartWithLights = true;
        setFullPracticeOptions(false);
        if (fullScreenDialog != null && fullPractice) songGuideView.setFeedback("正在同步灯光曲谱", cAccent());
    }
    private void restartFullScreenPractice(){
        if(pendingSongControlToken!=0||pendingUploadId!=null||!songQueue.isEmpty()){toast("正在处理上一操作，请稍候");return;}
        fullGuideAfterUploadId=null;guideUploadGeneration++;
        if((fullCountdown.active()&&fullCountdownDevice)||guideDeviceReference||hasDeviceSongIntent()||restartWithLights)startFullScreenGuide();
        else beginFullCountdown(false);
    }
    private void cancelFullCountdown(){
        boolean wasActive=fullCountdown.active();
        fullCountdown.cancel();if(fullCountdownTick!=null)main.removeCallbacks(fullCountdownTick);fullCountdownTick=null;
        updatePracticeCountdown(0);
        if(wasActive&&songGuideModeLabel!=null)songGuideModeLabel.setText("准备已取消");
    }
    private void updatePracticeCountdown(int remaining){
        if(songGuideView!=null)songGuideView.setCountdown(remaining);
        if(practiceCountdownLabel!=null){practiceCountdownLabel.setVisibility(remaining>0?View.VISIBLE:View.GONE);practiceCountdownLabel.setText(remaining>0?"准备开始  "+remaining:"");}
    }
    private void beginFullCountdown(final boolean device){
        final boolean replayAudition=!device&&auditionPracticeMode;
        if(device)auditionPracticeMode=false;
        stopScoreAudition();
        if(!activityVisible||selectedTimeline==null)return;
        final boolean fullscreenRequired=fullScreenDialog!=null&&fullPractice;
        if(device&&(!bleReady||!powerAllowsControls()||!canPlaySelectedSong())){toast("设备或曲谱尚未就绪");return;}
        setFullPracticeOptions(false);
        cancelFullCountdown();
        fullCountdownDevice=device;
        restartWithLights=device;
        if(hasDeviceSongIntent()||(deviceSongState!=null&&deviceSongState.state!=0))stopDeviceSong();
        else stopSongGuide("准备开始");
        guideDeviceReference=device;
        guideElapsedBase=0;songGuideView.setElapsed(0);songGuideView.clearDeviceEvent();
        final String song=selectedSongId;final int generation=fullCountdown.start(SystemClock.uptimeMillis());
        fullCountdownTick=new Runnable(){public void run(){
            if(!fullCountdown.active())return;
            if((!device&&fullscreenRequired&&(fullScreenDialog==null||!fullPractice))||!activityVisible||song==null||!song.equals(selectedSongId)||
               (device&&(!bleReady||!powerAllowsControls()))){cancelFullCountdown();return;}
            long now=SystemClock.uptimeMillis();int remaining=fullCountdown.remaining(now);
            updatePracticeCountdown(remaining);
            if(remaining>0){songGuideModeLabel.setText("准备开始 · "+remaining);main.postDelayed(this,Math.min(100,Math.max(1,fullCountdown.remainingMillis(now))));return;}
            if(!fullCountdown.complete(generation,now))return;
            fullCountdownTick=null;
            if(device){
                if(deviceStopRequested||pendingSongControlToken!=0){songGuideModeLabel.setText("设备停止尚未确认，请重试");toast("等待设备停止确认后再开始");return;}
                if(canPlaySelectedSong())playSelectedSong();
            }else if(replayAudition)playScoreAudition(auditionJumpToPractice);else startLocalPreview();
        }};
        main.post(fullCountdownTick);
    }
    private void openFullScreen(View view, boolean practice) {
        if (view == null || fullScreenDialog != null) return;
        if (practice && selectedTimeline == null) { toast("先选择曲谱"); return; }
        fullPractice = practice;
        ViewGroup parent = (ViewGroup) view.getParent();
        int index = parent.indexOfChild(view);
        ViewGroup.LayoutParams old = view.getLayoutParams();
        parent.removeView(view);
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar);
        fullScreenDialog = dialog;
        if (practice) {
            dialog.setContentView(buildFullPracticeStage((SongGuideView) view, dialog));
            dialog.setOnKeyListener((d, key, event) -> {
                if (key != android.view.KeyEvent.KEYCODE_BACK || fullPracticeOptions == null ||
                        fullPracticeOptions.getVisibility() != View.VISIBLE) return false;
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) setFullPracticeOptions(false);
                return true;
            });
        } else {
            LinearLayout content = column();
            content.setPadding(dp(16), dp(8), dp(16), dp(8));
            content.setBackgroundColor(cSurface());
            LinearLayout header = row();
            fullNoteLabel = kit.textBold(currentNote, ts(22), cTextPrimary());
            fullNoteLabel.setSingleLine(true);
            fullNoteLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
            header.addView(fullNoteLabel, new LinearLayout.LayoutParams(0, -2, 1));
            TextView done = outlineAction("退出全屏");
            fitSingleLine(done,12,10);
            done.setOnClickListener(v -> dialog.dismiss());
            header.addView(done);
            content.addView(header);
            content.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
            TextView rotate=outlineAction("旋转灯阵"), animate=outlineAction(ledAnimationPaused?"继续动画":"暂停动画");
            rotate.setOnClickListener(v->{ledLandscape=!ledLandscape;invalidateLedPreview();});
            animate.setOnClickListener(v->{ledAnimationPaused=!ledAnimationPaused;animate.setText(ledAnimationPaused?"继续动画":"暂停动画");ledPreview.setAnimating(!ledAnimationPaused);});
            addPracticeButtonRow(content,8,rotate,animate);
            dialog.setContentView(content);
        }
        dialog.setOnDismissListener(d -> {
            if(!fullCountdownDevice)cancelFullCountdown();
            if (practice) {
                view.setOnTouchListener(null);
                ((SongGuideView) view).setControlInset(0);
            }
            ((ViewGroup) view.getParent()).removeView(view);
            parent.addView(view, Math.min(index, parent.getChildCount()), old);
            fullScreenDialog=null;fullNoteLabel=null;fullScoreLabel=null;fullScoringSwitch=null;
            fullDirectionButton=null;fullStringOrderButton=null;fullGuideSceneButton=null;
            fullPracticeOptions=null;fullPracticeGear=null;fullGuideAfterUploadId=null;guideUploadGeneration++;
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            invalidateLedPreview();
        });
        dialog.show();
        android.view.Window window = dialog.getWindow();
        window.setLayout(-1, -1);
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(cSurface()));
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN |
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private FrameLayout buildFullPracticeStage(SongGuideView view, android.app.Dialog dialog) {
        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(cSurface());
        stage.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.setControlInset(dp(106));

        android.view.GestureDetector gestures = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(android.view.MotionEvent event) { return true; }
                    @Override public boolean onDoubleTap(android.view.MotionEvent event) {
                        if (!fullCountdown.active() && (guideRunning || localGuidePaused ||
                                (guideDeviceReference && hardwarePlayingId != null) ||
                                (auditionPlayer != null && auditionPlayer.isActive()))) {
                            tap();
                            pausePractice();
                        }
                        return true;
                    }
                });
        view.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));

        ScrollView options = new ScrollView(this);
        options.setBackground(kit.bg(cSurface(), 18, cStroke(), dp(1)));
        options.setElevation(dp(7));
        options.setVisibility(View.GONE);
        options.setClickable(true);
        fullPracticeOptions = options;
        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(dp(492), -2, Gravity.TOP | Gravity.END);
        placement.setMargins(dp(8), dp(52), dp(8), dp(8));
        stage.addView(options, placement);
        stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            int width = Math.max(1, Math.min(dp(492), r - l - dp(16)));
            if (placement.width != width) {
                placement.width = width;
                options.setLayoutParams(placement);
            }
        });

        LinearLayout panel = column();
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = kit.textBold("跟弹设置", ts(17), cTextPrimary());
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView exit = outlineAction("退出全屏");
        fitSingleLine(exit, 12, 6);
        exit.setOnClickListener(v -> dialog.dismiss());
        titleRow.addView(exit);
        panel.addView(titleRow);
        TextView song = kit.text(selectedSongName, ts(12), cTextSecondary());
        song.setSingleLine(true);
        song.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(song);
        panel.addView(buildFullPracticeControls());
        fullGuideSceneButton = outlineAction(guideSceneLabel());
        fullGuideSceneButton.setOnClickListener(v -> chooseGuideScene());
        TextView speed = outlineAction("练习倍速");
        speed.setOnClickListener(v -> choosePracticeSpeed());
        addPracticeButtonRow(panel, 8, fullGuideSceneButton, speed);

        LinearLayout score = row();
        score.setGravity(Gravity.CENTER_VERTICAL);
        score.setPadding(0, dp(4), 0, 0);
        fullScoreLabel = kit.textBold("", ts(12), cAccent());
        score.addView(fullScoreLabel, new LinearLayout.LayoutParams(0, -2, 1));
        score.addView(kit.text("评分", ts(12), cTextSecondary()));
        fullScoringSwitch = kit.toggle(scoringEnabled);
        score.addView(fullScoringSwitch);
        fullScoringSwitch.setOnCheckedChangeListener((v,on)->{
            if (scoringSwitch != null && scoringSwitch.isChecked() != on) scoringSwitch.setChecked(on);
        });
        panel.addView(score);
        options.addView(panel);

        LinearLayout edges = row();
        edges.setElevation(dp(8));
        ImageButton restart = fullPracticeIcon(R.drawable.ic_guide_restart, "重新开始");
        restart.setOnClickListener(v -> { tap(); restartFullScreenPractice(); });
        fullPracticeGear = fullPracticeIcon(R.drawable.ic_guide_settings, "跟弹设置");
        fullPracticeGear.setOnClickListener(v -> {
            tap();
            setFullPracticeOptions(options.getVisibility() != View.VISIBLE);
        });
        edges.addView(restart, new LinearLayout.LayoutParams(dp(44), dp(44)));
        addSpace(edges, 5);
        edges.addView(fullPracticeGear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        FrameLayout.LayoutParams edge = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        edge.setMargins(0, dp(4), dp(6), 0);
        stage.addView(edges, edge);
        updateScoringUi();
        return stage;
    }

    private ImageButton fullPracticeIcon(int resource, String label) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setColorFilter(cTextPrimary());
        button.setBackground(kit.ripple(cSurface2(), 24));
        button.setContentDescription(label);
        return button;
    }

    private void setFullPracticeOptions(boolean visible) {
        if (fullPracticeOptions == null) return;
        fullPracticeOptions.setVisibility(visible ? View.VISIBLE : View.GONE);
        fullPracticeGear.setContentDescription(visible ? "收起跟弹设置" : "跟弹设置");
    }

    /** Coordinates are (column,row): [lights,local,strings] / [pause,restart,baseline]. */
    private LinearLayout buildFullPracticeControls(){
        LinearLayout box=column();TextView lights=primaryAction("开始灯光指引"),local=outlineAction("开始无灯光指引"),pause=outlineAction("暂停 / 继续"),restart=outlineAction("重新开始");
        fullStringOrderButton=outlineAction(stringOrderText());fullDirectionButton=outlineAction(guideDirectionText(true));
        lights.setOnClickListener(v->{tap();startGuidedPractice();});local.setOnClickListener(v->{tap();requestLocalPractice();});
        pause.setOnClickListener(v->{tap();pausePractice();});restart.setOnClickListener(v->{tap();restartFullScreenPractice();});
        fullStringOrderButton.setOnClickListener(v->{tap();toggleGuideStrings();});fullDirectionButton.setOnClickListener(v->toggleGuideDirection());
        addPracticeButtonRow(box,6,lights,local,fullStringOrderButton);addPracticeButtonRow(box,8,pause,restart,fullDirectionButton);return box;
    }

    private void applyTrashRetention(){int days=getSharedPreferences("score_library",0).getInt("retention_days",0);if(days!=30&&days!=90)return;long before=System.currentTimeMillis()-days*86400000L;for(ScoreRepository.Item item:scoreRepo.list(true))if(item.deleted<before)try{scoreRepo.purge(item.id);}catch(Exception ignored){} }

    private void migrateLegacyScore(){try{String id=settings.lastSongId();if(id!=null&&id.startsWith("saved:"))return;if(!SettingsStore.KIND_IMPORT.equals(settings.lastSongKind()))return;byte[] bytes=decodeSongBytes(settings.lastSongAsset(),settings.lastSongBytes());if(bytes==null)return;SongTimeline.parse(bytes);ScoreRepository.Item item=scoreRepo.importFile(settings.lastSongName(),"application/x-uks",bytes);scoreRepo.setEvents(item,bytes,"历史导入 · 音域完整",0);settings.setLastSongId("saved:"+item.id);}catch(Exception ignored){} }

    /**
     * 在「曲库」与「练习」两个页面之间切换。
     *
     * 保留这个入口是因为代码里有多处「选好曲谱后直接进入练习」的流程（选中曲目、生成指引后练习、
     * 库页选中曲谱回主界面），它们只需要表达「现在去练习页」，不该各自记住页码。
     * 页面尚未构建时（启动早期）静默忽略。
     */
    private void showSongSection(boolean practice){
        if(pages==null||pages[2]==null||pages[3]==null)return;
        showPage(practice?3:2);
    }


    private LinearLayout buildScoringCard(){
        LinearLayout box=column(),head=row();head.addView(kit.textBold("练习评分",ts(14),cTextPrimary()),new LinearLayout.LayoutParams(0,-2,1));
        scoringSwitch=kit.toggle(scoringEnabled);head.addView(scoringSwitch);box.addView(head);
        scoreValue=kit.textBold("本轮 —",ts(20),cTextPrimary());box.addView(scoreValue);
        scoreDetail=kit.text("",ts(12),cTextSecondary());scoreDetail.setPadding(0,dp(4),0,dp(4));box.addView(scoreDetail);
        scoreBest=kit.textBold("最高成绩 —",ts(12),cAccent());box.addView(scoreBest);
        scoreRetry=outlineAction("开始评分练习");TextView review=outlineAction("AI 搭子点评"),tolerance=outlineAction("评分宽容");
        scoreRetry.setOnClickListener(v->{tap();requestLocalPractice();});review.setOnClickListener(v->{tap();aiReviewPractice();});
        tolerance.setOnClickListener(v->{tap();chooseScoringTolerance();});addPracticeButtonRow(box,8,scoreRetry,review);
        LinearLayout more=row();fitSingleLine(tolerance,12,8);more.addView(tolerance,new LinearLayout.LayoutParams(0,-2,1));
        TextView help=kit.text("实际拨弦核对发声音符",ts(11),cTextTertiary());help.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);more.addView(help,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams moreLp=new LinearLayout.LayoutParams(-1,-2);moreLp.topMargin=dp(8);box.addView(more,moreLp);
        scoringSwitch.setOnCheckedChangeListener((v,on)->{
            scoringEnabled=on;getSharedPreferences("practice_scoring",0).edit().putBoolean("enabled",on).apply();
            if(fullScoringSwitch!=null&&fullScoringSwitch.isChecked()!=on)fullScoringSwitch.setChecked(on);
            if(!on)abortScoring("评分已关闭，本轮不计成绩");
            else if(guideRunning||(auditionPlayer!=null&&auditionPlayer.isActive()))scoreHint="下次开始练习时计分";
            updateScoringUi();
        });updateScoringUi();return box;
    }
    private void chooseScoringTolerance(){
        final String[] names={"准时","标准","宽松"};java.util.List<AppDialogs.Item> rows=new ArrayList<>();
        for(int i=0;i<names.length;i++)rows.add(new AppDialogs.Item(names[i],"补偿设备音符反馈的传输时间",false,settings.practiceLead()==i?"当前":null));
        AppDialogs.list(this,"评分宽容",rows,i->{settings.setPracticeLead(i);PracticeScorer.setInputLead(PracticeScorer.leadForSetting(i));},"返回",null,null,null);
    }

    private void updateLeadButtons() {
        if (leadButtons == null || settings == null) return;
        int current = settings.practiceLead();
        for (int i = 0; i < leadButtons.length; ++i) setSegment(leadButtons[i], i == current);
    }

    private boolean scoringActive(){if(practiceScorer==null)return false;PracticeScorer.Snapshot s=practiceScorer.snapshot();return !s.finished&&!s.aborted;}
    private boolean canScoreNow(){return !instrumentIntent&&!(deviceSongState!=null&&deviceSongState.state!=0&&deviceSongState.instrument)&&bleReady&&powerAllowsControls()&&playedNoteTracker.ready(SystemClock.uptimeMillis());}
    private long scoringElapsed(){return scoreElapsedBase+(scorePaused?0:SystemClock.uptimeMillis()-scoreStartedAt);}
    private static String gradeText(int hundredths){return String.format(java.util.Locale.ROOT,"%d.%02d",hundredths/100,hundredths%100);}

    private void loadBestScore(){
        bestScore=null;visibleScoreKey=null;scoreSaving=false;
        if(selectedSongBytes==null){updateScoringUi();return;}
        try{final String key=PracticeScoreRecords.key(selectedSongBytes,guideSpeed)+"-capo"+activeCapo();visibleScoreKey=key;
            scoreWorker.execute(()->{try{PracticeScoreRecords.Record result=scoreRecords.read(key);main.post(()->{if(!isDestroyed()&&key.equals(visibleScoreKey)){bestScore=result;updateScoringUi();}});}catch(Exception e){main.post(()->{if(!isDestroyed()&&key.equals(visibleScoreKey)){scoreHint="最高成绩读取失败";updateScoringUi();}});}});
        }catch(Exception e){scoreHint="无法读取成绩";}updateScoringUi();
    }
    private void startScoredPractice(){
        auditionPracticeMode=false;
        stopScoreAudition();
        if(!scoringEnabled){toast("请先开启评分模式");return;}
        if(selectedTimeline==null||selectedSongBytes==null){toast("请先选择可练习曲谱");return;}
        if(!canScoreNow()){toast("请连接乐器并等待实时音符回读");updateScoringUi();return;}
        if(hasDeviceSongIntent())stopDeviceSong();
        startSongGuide(false);
        startScoringSession(0);
    }
    /** The countdown ends before this clock starts; device guidance uses the confirmed song position. */
    private void startScoringSession(long musicalPosition){
        if(!scoringEnabled)return;
        if(!canScoreNow()){scoreHint="等待实时音符反馈，请重新开始后计分";updateScoringUi();return;}
        try{
            practiceScorer=new PracticeScorer(selectedTimeline,guideSpeed);scoreRate=guideSpeed;scoreCapo=activeCapo();
            careerScorer=careerStage>=0&&("library:"+CAREER_IDS[careerStage]).equals(selectedSongId)?practiceScorer:null;
            scoreProfile=PracticeScoreRecords.key(selectedSongBytes,scoreRate)+"-capo"+scoreCapo;visibleScoreKey=scoreProfile;
            scoreGeneration++;scorePaused=false;scoreSaving=false;
            scoreElapsedBase=(long)(musicalPosition/scoreRate);scoreStartedAt=SystemClock.uptimeMillis();scoreHint="";
            if(!guideDeviceReference)songGuideModeLabel.setText("评分练习 · "+scoreRate+"×");
            main.removeCallbacks(scoreTick);main.post(scoreTick);updateScoringUi();
        }catch(Exception e){scoreHint="评分无法开始："+e.getMessage();updateScoringUi();}
    }
    private void abortScoring(String why){
        if(!scoringActive())return;if(careerScorer!=null){careerScorer=null;careerStage=-1;}practiceScorer.abort(why);scorePaused=false;main.removeCallbacks(scoreTick);scoreHint=why;updateScoringUi();
    }
    private void receiveScoringNotes(LiveNoteState notes){
        long now=SystemClock.uptimeMillis();
        PlayedNoteTracker.Batch batch=playedNoteTracker.accept(notes,now);
        if(batch.midi.length>0&&!instrumentIntent){
            int midi=batch.midi[0];
            for(int played:batch.midi)midi=Math.max(midi,played);
            selectEnvelopeNote(midi,modelReady()?modelState.actual:envelopeTimbre,activeCapo());
        }
        if(jamCanvas!=null && paintingDemo==null && batch.midi.length>0 && powerAllowsControls() && !instrumentIntent){
            jamCanvas.addNotes(batch.midi, modelReady() ? modelState.actual : -1, now);
        }
        if(performanceTake!=null && performanceTake.active()){
            if(notes==null || !powerAllowsControls() || instrumentIntent)performanceTake.stop();
            else performanceTake.accept(batch,notes.millis16);
            updateTakeUi();
        }
        if(scoringActive()){
            if(batch.fault!=null){abortScoring(batch.fault);stopSongGuide("评分已中止 · 反馈不可用");}
            else if(!scorePaused&&batch.midi.length>0){
                int[] sounding=batch.midi.clone();for(int i=0;i<sounding.length;i++)sounding[i]-=scoreCapo;
                PracticeScorer.Judgement j=practiceScorer.accept(scoringElapsed(),sounding);
                if(songGuideView!=null){if(j.wrong>0)songGuideView.setFeedback("错音",cDanger());else if(j.correct>0)songGuideView.setFeedback("正确",cSuccess());else if(j.extra>0)songGuideView.setFeedback("本音已判定",cWarn());}
                completeScoring();
            }
        }updateScoringUi();
    }
    private void showRecorder() {
        showPage(0);
        if (!(pages[0] instanceof ScrollView) || takeCard == null) return;
        ScrollView dashboard = (ScrollView) pages[0];
        View recorder = takeCard;
        Runnable reveal = () -> {
            if (currentPage != 0 || pages[0] != dashboard || takeCard != recorder) return;
            android.graphics.Rect bounds = new android.graphics.Rect();
            recorder.getDrawingRect(bounds);
            dashboard.offsetDescendantRectToMyCoords(recorder, bounds);
            dashboard.scrollTo(0, Math.max(0, bounds.top - dp(8)));
        };
        if (dashboard.isLaidOut() && !dashboard.isLayoutRequested()) reveal.run();
        else dashboard.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                  int oldL, int oldT, int oldR, int oldB) {
                v.removeOnLayoutChangeListener(this);
                reveal.run();
            }
        });
    }

    private String recordingBlockReason(){
        if(!bleReady)return "请先连接乐器";
        if(!powerAllowsControls())return "请先唤醒乐器";
        if(instrumentIntent || (deviceSongState!=null && deviceSongState.state!=0 && deviceSongState.instrument))
            return "请先停止乐器自动播放，再开始录奏";
        return "等待乐器实时音符回读，请稍后再试";
    }

    private View buildTakeCard(){
        LinearLayout panel=card();
        panel.addView(cardLabel("演奏录音机"));
        takeStatus=kit.text("",ts(13),cTextSecondary());takeStatus.setPadding(0,dp(8),0,dp(8));panel.addView(takeStatus);
        LinearLayout buttons=row();
        takeRecord=outlineAction("开始录奏");takeSave=outlineAction("存入曲库");
        buttons.addView(takeRecord,new LinearLayout.LayoutParams(0,-2,1));addSpace(buttons,8);
        buttons.addView(takeSave,new LinearLayout.LayoutParams(0,-2,1));panel.addView(buttons);
        takeRecord.setOnClickListener(v->{
            if(performanceTake!=null && performanceTake.active())performanceTake.stop();
            else if(!canScoreNow()){toast(recordingBlockReason());return;}
            else performanceTake=new PerformanceTake();
            updateTakeUi();
        });
        takeSave.setOnClickListener(v->{
            if(performanceTake==null || performanceTake.active())return;
            try{
                byte[] raw=performanceTake.midi();
                String name="演奏录音 "+new java.text.SimpleDateFormat("MM-dd HH-mm-ss",java.util.Locale.ROOT).format(new java.util.Date())+".mid";
                midiSkipped=0;
                byte[] events=normalizeSong(raw);
                if(events!=null)SongTimeline.parse(events);
                ScoreRepository.Item item=scoreRepo.saveRecording(name,raw);
                if(events!=null)scoreRepo.setEvents(item,events,midiSkipped==0?"音域完整":"部分音符未映射到琴弦",midiSkipped);
                performanceTake=null;updateTakeUi();rebuildSavedScores();selectSavedScore(item.id);showSongSection(false);
                toast("已存入曲库，可试听或用乐器重演");
            }catch(Exception e){toast("录奏保存失败："+e.getMessage());}
        });
        updateTakeUi();return panel;
    }
    private void updateTakeUi(){
        if(takeStatus==null)return;
        boolean recording=performanceTake!=null && performanceTake.active();
        int count=performanceTake==null?0:performanceTake.count();
        takeRecord.setText(recording?"停止录奏":"开始录奏");
        takeRecord.setEnabled(recording || count==0);
        takeSave.setEnabled(!recording && count>0);
        String status=recording?"录奏中 · "+count+" 个起音":count>0?"本段已停止 · "+count+" 个起音，等待保存":"记录拨弦音符与节奏，可在手机试听或用乐器重演";
        if(!status.contentEquals(takeStatus.getText()))takeStatus.setText(status);
    }
    private void tickScoring(){
        if(!scoringActive()){completeScoring();return;}
        if(!canScoreNow()){abortScoring("设备断线或音符反馈过期，本轮不计成绩");stopSongGuide("评分已中止 · 等待设备反馈");return;}
        long at=scoringElapsed();
        if(scorePaused){if(songGuideView!=null)songGuideView.setFeedback("已暂停",cTextSecondary());}
        else if(at<0){if(songGuideView!=null)songGuideView.setFeedback(String.valueOf((-at+999)/1000),cAccent());}
        else practiceScorer.advance(at);
        updateScoringUi();completeScoring();if(scoringActive())main.postDelayed(scoreTick,scorePaused?100:32);
    }
    private void completeScoring(){
        if(practiceScorer==null||scoreSaving)return;final PracticeScorer.Snapshot result=practiceScorer.snapshot();if(!result.recordable())return;
        final long playedDuration=practiceScorer.durationMs;
        final String attempt=java.util.UUID.randomUUID().toString();
        scoreSaving=true;main.removeCallbacks(scoreTick);final int token=scoreGeneration;final String key=scoreProfile;
        boolean careerRound=practiceScorer==careerScorer;
        if(careerRound) finishCareer(result);
        scoreHint="成绩保存中";updateScoringUi();
        scoreWorker.execute(()->{try{
            PracticeScoreRecords.Record previous=scoreRecords.read(key);
            PracticeScoreRecords.Record record=scoreRecords.save(key,result,System.currentTimeMillis());
            boolean improved=previous.total>0&&result.correct>previous.correct;
            PracticeJourneyStore.Reward earned;
            try{earned=journey.record(attempt,result.total,result.correct,playedDuration,true,improved);}catch(Exception e){earned=new PracticeJourneyStore.Reward(0,false,true);}
            final PracticeJourneyStore.Reward reward=earned;
            main.post(()->{
                if(isDestroyed())return;if(key.equals(visibleScoreKey))bestScore=record;unlockBadge(ACH_FIRST_SCORE);
                if(token==scoreGeneration){scoreHint=PracticeJourneyStore.stars(result.hundredths())+" "+(improved?"突破个人最佳":"本轮已完成")+(reward.saved&&reward.xp>0?" · +"+reward.xp+"经验":"");if(!reward.saved)scoreHint+=" · 成长记录暂未保存";}
                updateScoringUi();refreshJourney();
                if(!careerRound&&token==scoreGeneration&&activityVisible)showPracticeResult(result,reward,improved);
            });
        }catch(Exception e){main.post(()->{if(token==scoreGeneration){scoreHint="成绩保存失败，本轮分数仍可查看";updateScoringUi();}});}});
    }
    private void refreshJourney(){
        if(journey==null)return;PracticeJourneyStore.Snapshot j=journey.snapshot();
        if(journeyTitle!=null)journeyTitle.setText("音乐旅程 · Lv."+j.level()+"  "+j.xp%200+" / 200经验");
        if(journeyProgress!=null)journeyProgress.setProgress(j.xp%200);
        if(journeyTasks!=null)journeyTasks.setText("今日完成 "+j.quests()+" / 3项\n"+(j.runs>0?"✓":"○")+" 完整弹完一首（至少10秒、8音）\n"+(j.correct>=30?"✓":"○")+" 命中30个音  "+Math.min(30,j.correct)+" / 30\n"+(j.best>=8000?"✓":"○")+" 一轮达到80分  "+gradeText(j.best)+"分");
        if(journeyWeek!=null){StringBuilder b=new StringBuilder("近7天  ");for(boolean day:j.days)b.append(day?"●  ":"○  ");journeyWeek.setText(b.toString());}
        if(gameBests!=null)gameBests.setText("听音最高 "+j.earBest+"分 · 节奏最高 "+j.rhythmBest+"分");
    }
    private void openPracticeGame(boolean rhythm){
        if(scoringActive()){toast("先完成或结束当前评分，再玩小游戏");return;}
        stopScoreAudition();aiStopCandidatePreview();stopMetronome();if(chordPlayer!=null)chordPlayer.stop();
        if(practiceGame!=null)practiceGame.close();practiceGame=PracticeGamesDialog.open(this,rhythm,journey,this::refreshJourney);
    }
    private void showPracticeResult(PracticeScorer.Snapshot result,PracticeJourneyStore.Reward reward,boolean improved){
        String title=PracticeJourneyStore.stars(result.hundredths())+"  "+gradeText(result.hundredths())+"分";
        String message=(improved?"这次突破了自己的最高成绩！":"又完成了一次练习。")+"\n正确 "+result.correct+" · 错音 "+result.wrong+" · 漏弹 "+result.missed
                +(reward.xp>0?"\n获得 "+reward.xp+"点经验":"")+"\n下一步："+(result.hundredths()<6000?"先慢一点，争取60分":result.hundredths()<8000?"保持节奏，挑战80分":result.hundredths()<9500?"补齐漏音，冲击95分":"保持稳定，向满分前进");
        AppDialogs.message(this,title,message,"再挑战一次",(d,w)->{if(canScoreNow())requestLocalPractice();else toast("请先连接乐器");},"稍后",null,"搭子点评",(d,w)->aiReviewPractice());
    }

    private void updateScoringUi(){
        if(scoreValue==null)return;PracticeScorer.Snapshot s=practiceScorer==null?null:practiceScorer.snapshot();
        String state=s==null?(scoringEnabled?(canScoreNow()?"已就绪":"等待实时设备回读"):"评分未开启"):s.aborted?"已中止 · 不计最高分":s.finished?"已完成":scorePaused?"已暂停":scoringElapsed()<0?"准备中":"进行中";
        scoreValue.setText(s==null?"本轮 —":gradeText(s.hundredths())+" 分 · "+state);
        scoreValue.setTextColor(s!=null&&s.aborted?cWarn():cTextPrimary());
        scoreDetail.setText(s==null?state+(scoreHint.isEmpty()?"":"\n"+scoreHint):"正确 "+s.correct+" · 错音 "+s.wrong+" · 漏弹 "+s.missed+"\n已判 "+s.judged()+" / "+s.total+(s.extra>0?" · 多弹 "+s.extra:"")+(scoreHint.isEmpty()?"":"\n"+scoreHint));
        String highest=bestScore==null?(visibleScoreKey==null?"—":"读取中"):bestScore.attempts==0?"—":gradeText(bestScore.hundredths())+" 分";
        scoreBest.setText("最高成绩 "+highest+" · "+guideSpeed+"×"+(bestScore!=null&&bestScore.attempts>0?" · 完成 "+bestScore.attempts+" 次":""));
        scoreRetry.setText(s!=null&&(s.finished||s.aborted)?"再挑战一次":"开始评分练习");scoreRetry.setEnabled(scoringEnabled&&selectedTimeline!=null&&canScoreNow());scoreRetry.setAlpha(scoreRetry.isEnabled()?1f:.45f);
        if(fullScoreLabel!=null)fullScoreLabel.setText(rateText(guideSpeed)+" · "+(s==null?(scoringEnabled?"评分已开启 · 下次开始计分":"评分未开启"):("本轮 "+gradeText(s.hundredths())+" 分 · "+state+"    最高 "+highest)));
    }

    // =====================================================================
    // v3.11.0 「乐趣」页：打卡/成就 + 调音器 + 节拍器 + 和弦库 + 音乐问答
    //
    // 设计边界：这一页全部是手机侧功能，不新增任何 BLE 指令或设备状态；
    // 调音器只读取既有的 0xA1 完整分析帧，节拍器/和弦/问答只使用手机扬声器。
    // 这样新增玩法不会影响原有演奏、灯阵、曲谱与评分链路的任何行为。
    // =====================================================================

    private android.content.SharedPreferences careerProgress(){
        return getSharedPreferences("star_career",0);
    }

    private SongLibrary.Song careerSong(int index){
        for(SongLibrary.Song song:librarySongs)if(CAREER_IDS[index].equals(song.id))return song;
        return null;
    }

    private void openCareer(){
        if(scoringActive()||fullCountdown.active()){toast("先结束当前练习");return;}
        int unlocked=careerProgress().getInt("unlocked",0);
        java.util.List<AppDialogs.Item> items=new ArrayList<>();
        for(int i=0;i<CAREER_IDS.length;i++){
            SongLibrary.Song song=careerSong(i);
            String detail=song==null?"曲目不可用":song.title+" · "+CAREER_PASS[i]+"分过关";
            String status=i<unlocked?"已通关":i==unlocked?"当前关卡":"未解锁";
            items.add(new AppDialogs.Item((i+1)+"  "+CAREER_STAGES[i],detail,false,status));
        }
        AppDialogs.list(this,"星河旅程",items,i->{
            if(i>unlocked){toast("通关上一站后解锁");return;}
            if(careerProgress().getInt("fail_"+i,0)>2){
                AppDialogs.message(this,"重新出发","这一站的复活次数已用完。新一轮会恢复两次复活，已解锁的关卡会保留。",
                        "开始新一轮",(d,w)->{
                            if(startCareer(i))careerProgress().edit().remove("fail_"+i).apply();
                        },"返回",null,null,null);
            }else startCareer(i);
        },"返回",null,null,null);
    }

    private boolean startCareer(int index){
        if(!canScoreNow()){toast("请连接乐器并等待实时音符回读");return false;}
        SongLibrary.Song song=careerSong(index);
        if(song==null){toast("曲目暂不可用");return false;}
        selectLibrarySong(song);
        if(!("library:"+song.id).equals(selectedSongId)||!capoRangePlayable())return false;
        careerStage=index;
        if(scoringSwitch!=null)scoringSwitch.setChecked(true);
        setGuideScene(GuideScene.STARS);
        showSongSection(true);
        requestLocalPractice();
        return true;
    }

    private void finishCareer(PracticeScorer.Snapshot result){
        int stage=careerStage;
        careerScorer=null;careerStage=-1;
        if(stage<0)return;
        int unlocked=careerProgress().getInt("unlocked",0);
        boolean passed=result.hundredths()>=CAREER_PASS[stage]*100;
        android.content.SharedPreferences.Editor update=careerProgress().edit();
        int failures=passed?0:careerProgress().getInt("fail_"+stage,0)+1;
        update.putInt("fail_"+stage,failures);
        if(passed)update.putInt("unlocked",Math.max(unlocked,stage+1));
        update.apply();
        if(!activityVisible)return;
        String detail=gradeText(result.hundredths())+"分 · 命中 "+result.correct+" / "+result.total;
        if(passed){
            boolean more=stage+1<CAREER_IDS.length;
            AppDialogs.message(this,more?"点亮 "+CAREER_STAGES[stage]:"星河旅程完成",detail,
                    more?"前往下一站":"返回旅程",(d,w)->{if(more)startCareer(stage+1);else openCareer();},"稍后",null,null,null);
        }else{
            boolean revivable=failures<=2;
            String remaining=revivable?"还可复活 "+(3-failures)+" 次，复活后从本曲开头重弹。":"本轮复活已用完，已解锁的关卡会保留。";
            AppDialogs.message(this,"这一站再试一次",detail+"\n"+remaining,
                    revivable?"复活重试":"返回旅程",(d,w)->{if(revivable)startCareer(stage);else openCareer();},"稍后",null,null,null);
        }
    }

    private void openJamCanvas(){
        if(jamDialog!=null)return;
        android.app.Dialog dialog=new android.app.Dialog(this,android.R.style.Theme_Material_NoActionBar);
        jamDialog=dialog;
        LinearLayout body=column();body.setBackgroundColor(AppTheme.colors().bgRoot);
        body.setPadding(dp(20),dp(14),dp(20),dp(20));
        LinearLayout header=row();
        header.addView(kit.textBold("畅想时刻",ts(24),cTextPrimary()),new LinearLayout.LayoutParams(0,-2,1));
        TextView close=outlineAction("完成");close.setOnClickListener(v->dialog.dismiss());header.addView(close);body.addView(header);
        TextView info=hint("把旋律留在夜空。音高落成光轨，和弦同时绽开，拨弦的疏密改变笔触。");
        info.setPadding(0,dp(8),0,dp(12));body.addView(info);
        JamCanvasView canvas=new JamCanvasView(this);jamCanvas=canvas;
        body.addView(canvas,new LinearLayout.LayoutParams(-1,0,1));
        TextView freeze=outlineAction("暂停作画"),clear=outlineAction("清空");
        freeze.setOnClickListener(v->{canvas.setFrozen(!canvas.isFrozen());freeze.setText(canvas.isFrozen()?"继续作画":"暂停作画");});
        clear.setOnClickListener(v->{stopPaintingDemo();canvas.clear();});
        addPracticeButtonRow(body,12,freeze,clear);
        TextView demo=outlineAction("看一段成画"),save=outlineAction("保存为图片");
        demo.setOnClickListener(v->{canvas.setFrozen(false);freeze.setText("暂停作画");startPaintingDemo(canvas);});
        save.setOnClickListener(v->exportPainting(canvas));
        addPracticeButtonRow(body,8,demo,save);
        dialog.setContentView(body);
        dialog.setOnDismissListener(d->{stopPaintingDemo();jamCanvas=null;jamDialog=null;});
        dialog.show();AppTheme.applyWindow(dialog.getWindow());dialog.getWindow().setLayout(-1,-1);
    }

    private void stopPaintingDemo(){
        if(paintingDemo!=null){main.removeCallbacks(paintingDemo);paintingDemo=null;}
    }
    private void startPaintingDemo(JamCanvasView canvas){
        stopPaintingDemo();canvas.clear();
        int[][] motif={{60},{64},{67},{72,76},{74},{71,67},{64},{69},{76,79},{72},{74},{81,76},{79},{74},{67,72}};
        int[] gaps={390,340,220,540,310,560,270,260,500,410,260,580,380,340,360};
        paintingDemo=new Runnable(){
            int step;
            public void run(){
                if(paintingDemo!=this||jamCanvas!=canvas)return;
                if(canvas.isFrozen()){main.postDelayed(this,100);return;}
                canvas.addNotes(motif[step],(step/4)%4,SystemClock.uptimeMillis());
                int gap=gaps[step++];
                if(step==motif.length)paintingDemo=null;else main.postDelayed(this,gap);
            }
        };
        main.post(paintingDemo);
    }
    private void exportPainting(JamCanvasView canvas){
        if(paintToExport!=null)return;
        paintToExport=canvas.snapshot();
        if(paintToExport==null){toast("先弹一段旋律或看看成画演示");return;}
        Intent out=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/png").addCategory(Intent.CATEGORY_OPENABLE);
        out.putExtra(Intent.EXTRA_TITLE,"尤克里里 · 琴声画境.png");
        try{startActivityForResult(out,REQ_PAINT_EXPORT);}
        catch(Exception e){paintToExport.recycle();paintToExport=null;toast("无法打开文件选择器");}
    }

    private View buildFunPage() {
        LinearLayout body = column();
        body.setPadding(dp(20), dp(4), dp(20), dp(24));

        body.addView(sectionTitle("创作与旅程"));
        LinearLayout creative=card();
        TextView jam=primaryAction("畅想时刻 · 用琴声作画");
        jam.setOnClickListener(v->{tap();openJamCanvas();});
        creative.addView(jam);
        TextView career=outlineAction("星河旅程 · 生涯闯关");
        career.setOnClickListener(v->{tap();openCareer();});
        addPracticeButtonRow(creative,10,career);
        addContent(body,creative);

        // ---- AI 音乐搭子（v3.12.0 新增，放在最上面作为乐趣页的门面）----
        body.addView(sectionTitle("AI 音乐搭子"));
        LinearLayout aiFunCard = card();
        LinearLayout aiFunHead = row();
        aiFunPersonaLabel = kit.textBold("AI 音乐搭子", ts(16), cTextPrimary());
        aiFunHead.addView(aiFunPersonaLabel, new LinearLayout.LayoutParams(0, -2, 1));
        aiFunHead.addView(badge("DeepSeek", cAccentSoft(), cAccent()));
        aiFunCard.addView(aiFunHead);
        aiFunStatusLabel = hint("点下方按钮配置 API Key 或使用官方通道，即可开始聊天。");
        aiFunStatusLabel.setPadding(0, dp(6), 0, 0);
        aiFunCard.addView(aiFunStatusLabel);
        TextView aiFunChat = primaryAction("开始聊天 / 语音对话");
        aiFunChat.setOnClickListener(v -> { tap(); openAiChat(); });
        LinearLayout.LayoutParams aiFunChatLp = new LinearLayout.LayoutParams(-1, -2);
        aiFunChatLp.topMargin = dp(12);
        aiFunCard.addView(aiFunChat, aiFunChatLp);
        TextView aiFunReview = outlineAction("让搭子点评这轮练习");
        aiFunReview.setOnClickListener(v -> { tap(); aiReviewPractice(); });
        LinearLayout.LayoutParams aiFunReviewLp = new LinearLayout.LayoutParams(-1, -2);
        aiFunReviewLp.topMargin = dp(8);
        aiFunCard.addView(aiFunReview, aiFunReviewLp);
        aiFunCard.addView(hint("最多可以养 4 个搭子：聊音乐、聊生活、聊灵感都行；聊天页里可以换人格、换声音、开连续对话。"));
        addContent(body, aiFunCard);

        // ---- 今日打卡 ----
        body.addView(sectionTitle("每日挑战"));
        LinearLayout journeyCard=card();
        journeyTitle=kit.textBold("音乐旅程 · Lv.1",ts(21),cAccent());journeyCard.addView(journeyTitle);
        journeyProgress=kit.linearProgress(cAccent());journeyProgress.setMax(200);LinearLayout.LayoutParams progressLp=new LinearLayout.LayoutParams(-1,dp(7));progressLp.topMargin=dp(12);progressLp.bottomMargin=dp(12);journeyCard.addView(journeyProgress,progressLp);
        journeyTasks=kit.text("",ts(14),cTextPrimary());journeyTasks.setLineSpacing(dp(7),1);journeyCard.addView(journeyTasks);
        journeyWeek=kit.text("",ts(12),cTextSecondary());journeyWeek.setPadding(0,dp(12),0,dp(10));journeyCard.addView(journeyWeek);
        TextView challenge=primaryAction("开始今日挑战");challenge.setOnClickListener(v->{tap();showPage(3);});journeyCard.addView(challenge);
        addContent(body,journeyCard);
        body.addView(sectionTitle("音乐游乐场"));
        LinearLayout gameCard=card();LinearLayout gameRow=row();TextView ear=outlineAction("听音挑战"),rhythm=outlineAction("节奏模仿");
        ear.setOnClickListener(v->{tap();openPracticeGame(false);});rhythm.setOnClickListener(v->{tap();openPracticeGame(true);});
        gameRow.addView(ear,new LinearLayout.LayoutParams(0,-2,1));addSpace(gameRow,10);gameRow.addView(rhythm,new LinearLayout.LayoutParams(0,-2,1));rowMinHeight(gameRow,50);gameCard.addView(gameRow);
        gameBests=hint("");gameBests.setPadding(0,dp(10),0,0);gameCard.addView(gameBests);addContent(body,gameCard);

        body.addView(sectionTitle("今日练习"));
        LinearLayout checkInCard = card();
        LinearLayout checkInHead = row();
        funStreakLabel = kit.textBold("连续 0 天", ts(24), cAccent());
        checkInHead.addView(funStreakLabel, new LinearLayout.LayoutParams(0, -2, 1));
        funCheckInLabel = badge("今日未打卡", cSurface3(), cTextSecondary());
        checkInHead.addView(funCheckInLabel);
        checkInCard.addView(checkInHead);
        funDaysLabel = hint("累计打卡 0 天 · 最长连续 0 天");
        funDaysLabel.setPadding(0, dp(6), 0, 0);
        checkInCard.addView(funDaysLabel);
        LinearLayout badgeLine = row();
        badgeLine.setPadding(0, dp(14), 0, dp(6));
        funBadgesLabel = kit.textBold("成就 0 / 10", ts(13), cTextSecondary());
        badgeLine.addView(funBadgesLabel, new LinearLayout.LayoutParams(0, -2, 1));
        badgeLine.addView(kit.text("练琴、连线、玩小工具都能解锁", ts(11), cTextTertiary()));
        checkInCard.addView(badgeLine);
        funBadgeProgress = kit.linearProgress(cAccent());
        funBadgeProgress.setMax(FunStore.badges().length);
        checkInCard.addView(funBadgeProgress);
        TextView funPractice = primaryAction("去练习一首曲目");
        funPractice.setOnClickListener(v -> { tap(); showPage(3); });
        LinearLayout.LayoutParams funPracticeLp = new LinearLayout.LayoutParams(-1, -2);
        funPracticeLp.topMargin = dp(14);
        checkInCard.addView(funPractice, funPracticeLp);
        addContent(body, checkInCard);

        // ---- 成就徽章 ----
        body.addView(sectionTitle("成就徽章"));
        LinearLayout badgeCard = card();
        funBadgeGrid = column();
        badgeCard.addView(funBadgeGrid);
        addContent(body, badgeCard);

        // ---- 调音器 ----
        body.addView(sectionTitle("调音器"));
        LinearLayout tunerCard = card();
        LinearLayout tunerHead = row();
        TextView tunerTitle = cardLabel("实时音高 · 四弦目标");
        tunerHead.addView(tunerTitle, new LinearLayout.LayoutParams(0, -2, 1));
        tunerHead.addView(badge("GCEA", cAccentSoft(), cAccent()));
        tunerCard.addView(tunerHead);
        tunerCard.addView(hint("连接琴后拨动一根弦；指针居中并显示「准」即音准正确。数据来自设备频谱，不占用手机麦克风。"));
        tunerGauge = new TunerGaugeView(this);
        LinearLayout.LayoutParams tunerGaugeLp = new LinearLayout.LayoutParams(-1, dp(216));
        tunerGaugeLp.topMargin = dp(10);
        tunerCard.addView(tunerGauge, tunerGaugeLp);
        addContent(body, tunerCard);

        // ---- 节拍器 ----
        body.addView(sectionTitle("节拍器"));
        LinearLayout metroCard = card();
        LinearLayout metroHead = row();
        metronomeBpmLabel = kit.textBold(String.valueOf(metronomeBpm), ts(34), cTextPrimary());
        metroHead.addView(metronomeBpmLabel, new LinearLayout.LayoutParams(0, -2, 1));
        metroHead.addView(kit.text("BPM", ts(13), cTextSecondary()));
        metroCard.addView(metroHead);
        metronomeBeatLabel = hint("第 1 / " + metronomeBeats + " 拍 · 未开始");
        metronomeBeatLabel.setPadding(0, dp(2), 0, dp(6));
        metroCard.addView(metronomeBeatLabel);
        metronomeBar = kit.seekBar(cAccent());
        metronomeBar.setMax(MetronomeEngine.MAX_BPM - MetronomeEngine.MIN_BPM);
        metronomeBar.setProgress(Math.max(0, Math.min(metronomeBar.getMax(), metronomeBpm - MetronomeEngine.MIN_BPM)));
        metronomeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                if (!fromUser) return;
                // 拖动过程中只更新 UI 与引擎；松手再持久化，避免一个拖动写几十次 SharedPreferences。
                int bpm = MetronomeEngine.clampBpm(MetronomeEngine.MIN_BPM + p);
                metronomeBpm = bpm;
                if (metronomeBpmLabel != null) metronomeBpmLabel.setText(String.valueOf(bpm));
                if (metronome != null && metronome.isRunning()) metronome.setBpm(bpm);
            }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { applyMetronomeBpm(MetronomeEngine.MIN_BPM + b.getProgress(), false); }
        });
        metroCard.addView(metronomeBar);
        LinearLayout beatRow = row();
        String[] beatNames = {"2/4", "3/4", "4/4", "6/8"};
        final int[] beatValues = {2, 3, 4, 6};
        final TextView[] beatButtons = new TextView[beatNames.length];
        for (int i = 0; i < beatNames.length; ++i) {
            final int beats = beatValues[i];
            final int beatIndex = i;
            TextView b = segment(beatNames[i], metronomeBeats == beats);
            beatButtons[i] = b;
            b.setOnClickListener(v -> {
                tap();
                metronomeBeats = beats;
                for (int k = 0; k < beatButtons.length; ++k) setSegment(beatButtons[k], k == beatIndex);
                if (metronome != null && metronome.isRunning()) metronome.setBeatsPerBar(beats);
                updateMetronomeDots(-1, beats);
                metronomeBeatLabel.setText("第 1 / " + beats + " 拍 · " +
                        (metronome != null && metronome.isRunning() ? "播放中" : "未开始"));
            });
            beatRow.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
            if (i < beatNames.length - 1) addSpace(beatRow, 8);
        }
        LinearLayout.LayoutParams beatLp = new LinearLayout.LayoutParams(-1, -2);
        beatLp.topMargin = dp(6);
        rowMinHeight(beatRow, 42);
        metroCard.addView(beatRow, beatLp);
        LinearLayout dotRow = row();
        dotRow.setGravity(Gravity.CENTER);
        dotRow.setClipChildren(false);
        dotRow.setClipToPadding(false);
        // 放大后的圆点仍落在预留空间内，避免被行边界或下方按钮裁切。
        dotRow.setPadding(0, dp(8), 0, dp(8));
        for (int i = 0; i < metronomeBeatDots.length; ++i) {
            View dot = new View(this);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(cSurface4());
            dot.setBackground(circle);
            dot.setContentDescription("第 " + (i + 1) + " 拍");
            metronomeBeatDots[i] = dot;
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(16), dp(16));
            dotLp.leftMargin = dp(8);
            dotLp.rightMargin = dp(8);
            dotRow.addView(dot, dotLp);
        }
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-1, -2);
        dotsLp.topMargin = dp(8);
        metroCard.addView(dotRow, dotsLp);
        LinearLayout metroActions = row();
        metronomeStartButton = primaryAction("▶ 开始节拍");
        TextView tapTempo = outlineAction("轻拍定速");
        metroActions.addView(metronomeStartButton, new LinearLayout.LayoutParams(0, -2, 1));
        addSpace(metroActions, 10);
        metroActions.addView(tapTempo, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams metroActionsLp = new LinearLayout.LayoutParams(-1, -2);
        metroActionsLp.topMargin = dp(18);
        rowMinHeight(metroActions, 46);
        metroCard.addView(metroActions, metroActionsLp);
        metronomeStartButton.setOnClickListener(v -> toggleMetronome());
        tapTempo.setOnClickListener(v -> registerTempoTap());
        addContent(body, metroCard);
        // 默认 4/4 只显示四个点，切换拍号时重新居中排列。
        updateMetronomeDots(-1, metronomeBeats);

        // ---- 和弦库（两行高的横向列表，避免 18 个和弦把页面拉得过长） ----
        body.addView(sectionTitle("和弦库"));
        LinearLayout chordCard = card();
        chordCard.addView(hint("点击和弦试听标准扫弦；左右滑动查看全部 " + ChordLibrary.count() + " 个常用和弦。"));
        HorizontalScrollView chordScroll = new HorizontalScrollView(this);
        chordScroll.setHorizontalScrollBarEnabled(false);
        chordScroll.setClipToPadding(false);
        LinearLayout chordRow = row();
        chordRow.setPadding(0, dp(12), dp(6), 0);
        ChordLibrary.Chord[] chords = ChordLibrary.all();
        for (ChordLibrary.Chord chord : chords) {
            LinearLayout cell = column();
            cell.setPadding(dp(8), dp(8), dp(8), dp(8));
            cell.setBackground(kit.ripple(cSurface2(), 14));
            cell.setClickable(true); cell.setFocusable(true);
            ChordDiagramView diagram = new ChordDiagramView(this);
            diagram.setChord(chord);
            cell.addView(diagram, new LinearLayout.LayoutParams(dp(104), dp(124)));
            TextView name = kit.textBold(chord.name, ts(14), cTextPrimary());
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, dp(4), 0, 0);
            cell.addView(name);
            cell.setOnClickListener(v -> playChord(chord));
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(dp(122), -2);
            cellLp.rightMargin = dp(8);
            chordRow.addView(cell, cellLp);
        }
        chordScroll.addView(chordRow);
        chordCard.addView(chordScroll);
        addContent(body, chordCard);

        // ---- 音乐小问答 ----
        body.addView(sectionTitle("音乐小问答"));
        LinearLayout quizCard = card();
        LinearLayout quizHead = row();
        quizHead.addView(cardLabel("答对越多，连对越高"), new LinearLayout.LayoutParams(0, -2, 1));
        quizStreakLabel = badge("连对 0", cSurface3(), cTextSecondary());
        quizHead.addView(quizStreakLabel);
        quizCard.addView(quizHead);
        quizQuestionLabel = kit.textBold("加载题目…", ts(15), cTextPrimary());
        quizQuestionLabel.setPadding(0, dp(10), 0, dp(8));
        quizQuestionLabel.setLineSpacing(dp(3), 1f);
        quizCard.addView(quizQuestionLabel);
        for (int i = 0; i < quizOptionButtons.length; ++i) {
            final int optionIndex = i;
            TextView option = outlineAction("");
            option.setPadding(dp(14), dp(10), dp(14), dp(10));
            option.setOnClickListener(v -> answerQuiz(optionIndex));
            LinearLayout.LayoutParams optionLp = new LinearLayout.LayoutParams(-1, -2);
            optionLp.bottomMargin = dp(8);
            quizOptionButtons[i] = option;
            quizCard.addView(option, optionLp);
        }
        quizFeedbackLabel = hint("");
        quizFeedbackLabel.setPadding(0, dp(2), 0, dp(6));
        quizCard.addView(quizFeedbackLabel);
        TextView nextQuestion = outlineAction("下一题");
        nextQuestion.setOnClickListener(v -> { tap(); newQuizQuestion(); });
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(-1, -2);
        nextLp.topMargin = dp(4);
        quizCard.addView(nextQuestion, nextLp);
        addContent(body, quizCard);

        refreshFunPage();
        newQuizQuestion();
        return page(body);
    }

    /** 刷新乐趣页的打卡、成就与问答状态；页面未构建时静默跳过。 */
    private void refreshFunPage() {
        refreshJourney();
        if (funStore == null) return;
        // 进入页面时补一次打卡：覆盖“跨零点仍停在页面”的情况；同一天重复调用不会增加天数。
        funStore.checkInToday();
        int streak = funStore.streak();
        if (funStreakLabel != null) funStreakLabel.setText("连续 " + streak + " 天");
        if (funDaysLabel != null) funDaysLabel.setText("累计打卡 " + funStore.totalDays() + " 天 · 最长连续 " + funStore.maxStreak() + " 天");
        if (funCheckInLabel != null) {
            funCheckInLabel.setText("今日已打卡");
            funCheckInLabel.setBackground(kit.bg(cAccentSoft(), 999));
            funCheckInLabel.setTextColor(cAccent());
        }
        if (funBadgesLabel != null) funBadgesLabel.setText("成就 " + funStore.unlockedCount() + " / " + FunStore.badges().length);
        if (funBadgeProgress != null) {
            funBadgeProgress.setMax(FunStore.badges().length);
            funBadgeProgress.setProgress(funStore.unlockedCount());
        }
        if (quizStreakLabel != null) quizStreakLabel.setText("连对 " + funStore.quizStreak());
        rebuildFunBadges();
        if (streak >= 3) unlockBadge(ACH_STREAK_3);
        refreshDashboardHero();
    }

    /** 成就格：2 列，已解锁用 accent，未解锁用三级文字并标「未解锁」。 */
    private void rebuildFunBadges() {
        if (funBadgeGrid == null || funStore == null) return;
        funBadgeGrid.removeAllViews();
        FunStore.Badge[] badges = FunStore.badges();
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        grid.setUseDefaultMargins(false);
        for (int i = 0; i < badges.length; ++i) {
            FunStore.Badge badge = badges[i];
            boolean unlocked = funStore.isUnlocked(badge.id);
            LinearLayout cell = column();
            cell.setPadding(dp(10), dp(9), dp(10), dp(9));
            cell.setBackground(kit.bg(unlocked ? cAccentSoft() : cSurface2(), 12));
            LinearLayout titleRow = row();
            titleRow.addView(kit.text("●", ts(11), unlocked ? cAccent() : cTextTertiary()));
            TextView title = kit.textBold(badge.title, ts(13), unlocked ? cAccent() : cTextSecondary());
            title.setPadding(dp(5), 0, 0, 0);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            titleRow.addView(kit.text(unlocked ? "已解锁" : "未解锁", ts(10), unlocked ? cAccent() : cTextTertiary()));
            cell.addView(titleRow);
            TextView desc = kit.text(badge.desc, ts(11), unlocked ? cTextSecondary() : cTextTertiary());
            desc.setPadding(0, dp(3), 0, 0);
            cell.addView(desc);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0; lp.height = -2;
            lp.columnSpec = GridLayout.spec(i % 2, 1, 1);
            lp.rowSpec = GridLayout.spec(i / 2);
            lp.setMargins(0, 0, i % 2 == 0 ? dp(10) : 0, dp(8));
            grid.addView(cell, lp);
        }
        funBadgeGrid.addView(grid);
    }

    /** 成就解锁：首次解锁时震动 + 轻提示并刷新乐趣页；重复调用无副作用。 */
    private void unlockBadge(String badgeId) {
        if (funStore == null || badgeId == null) return;
        if (!funStore.unlock(badgeId)) return;
        String title = FunStore.badgeTitle(badgeId);
        if (title != null && !title.isEmpty()) toast("成就解锁 · " + title);
        if (feedback != null) feedback.confirm();
        // 只有正在看乐趣页时才重建徽章格；不在该页时等下次进入 refreshFunPage() 统一刷新，
        // 避免 BLE 回调里给不可见页面反复创建几十个 View。已解锁徽章不会再次进入这里，不会递归。
        if (funBadgeGrid != null && currentPage == PAGE_FUN) refreshFunPage();
    }

    // ---- 节拍器 ----

    private MetronomeEngine ensureMetronome() {
        if (metronome == null) metronome = new MetronomeEngine();
        return metronome;
    }

    private void toggleMetronome() {
        if (ensureMetronome().isRunning()) { stopMetronome(); return; }
        tap();
        boolean started = metronome.start(metronomeBpm, metronomeBeats, (beatIndex, beats) -> onMetronomeBeat(beatIndex, beats));
        if (!started) { toast("手机音频输出不可用，无法播放节拍"); return; }
        if (funStore != null) {
            funStore.setMetronomeBpm(metronomeBpm);
            unlockBadge(ACH_METRONOME_MASTER);
        }
        if (metronomeStartButton != null) metronomeStartButton.setText("■ 停止节拍");
        if (metronomeBeatLabel != null) metronomeBeatLabel.setText("第 1 / " + metronomeBeats + " 拍 · 播放中");
    }

    private void stopMetronome() {
        if (metronome != null) metronome.stop();
        if (metronomeStartButton != null) metronomeStartButton.setText("▶ 开始节拍");
        if (metronomeBeatLabel != null) metronomeBeatLabel.setText("第 1 / " + metronomeBeats + " 拍 · 已停止");
        updateMetronomeDots(-1, metronomeBeats);
    }

    private void applyMetronomeBpm(int bpm, boolean syncSlider) {
        bpm = MetronomeEngine.clampBpm(bpm);
        metronomeBpm = bpm;
        if (metronomeBpmLabel != null) metronomeBpmLabel.setText(String.valueOf(bpm));
        if (syncSlider && metronomeBar != null) metronomeBar.setProgress(Math.max(0, Math.min(metronomeBar.getMax(), bpm - MetronomeEngine.MIN_BPM)));
        if (metronome != null && metronome.isRunning()) metronome.setBpm(bpm);
        if (funStore != null) funStore.setMetronomeBpm(bpm);
    }

    /** 轻拍定速：取最近几次点击的平均间隔换算 BPM；停顿超过 2 秒重新开始计数。 */
    private void registerTempoTap() {
        long now = SystemClock.uptimeMillis();
        if (metronomeTapCount > 0 && now - metronomeTaps[(metronomeTapCount - 1) % metronomeTaps.length] > 2000) metronomeTapCount = 0;
        metronomeTaps[metronomeTapCount % metronomeTaps.length] = now;
        metronomeTapCount++;
        if (metronomeTapCount < 2) { toast("再拍几下，估算速度"); return; }
        int samples = Math.min(metronomeTapCount - 1, metronomeTaps.length - 1);
        long total = 0;
        for (int i = 0; i < samples; ++i) {
            int newer = (metronomeTapCount - 1 - i) % metronomeTaps.length;
            int older = (metronomeTapCount - 2 - i) % metronomeTaps.length;
            total += Math.max(1, metronomeTaps[newer] - metronomeTaps[older]);
        }
        applyMetronomeBpm((int) Math.round(60000d * samples / Math.max(1, total)), true);
        if (metronomeTapCount >= metronomeTaps.length) { metronomeTapCount = 1; metronomeTaps[0] = now; }
        tap();
    }

    /** 节拍高亮：只改圆点颜色与缩放，不参与音频时序（时序由 MetronomeEngine 保证）。 */
    private void onMetronomeBeat(int beatIndex, int beatsPerBar) {
        int beats = Math.max(1, beatsPerBar);
        int active = ((beatIndex % beats) + beats) % beats;
        updateMetronomeDots(active, beats);
        if (metronomeBeatLabel != null) metronomeBeatLabel.setText("第 " + (active + 1) + " / " + beats + " 拍 · 播放中");
    }

    private void updateMetronomeDots(int active, int beats) {
        for (int i = 0; i < metronomeBeatDots.length; ++i) {
            View dot = metronomeBeatDots[i];
            if (dot == null) continue;
            boolean within = i < beats;
            int visibility = within ? View.VISIBLE : View.GONE;
            if (dot.getVisibility() != visibility) dot.setVisibility(visibility);
            ((GradientDrawable) dot.getBackground()).setColor(within && i == active ? cAccent() : cSurface4());
            float scale = within && i == active ? 1.35f : 1f;
            dot.setScaleX(scale); dot.setScaleY(scale);
        }
    }

    // ---- 和弦试听 ----

    private ChordPlayer ensureChordPlayer() {
        // 带 Context 的构造会申请临时音频焦点（AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK），
        // 播放结束/停止时释放，避免和弦试听把别的 App 音乐顶掉后不还回来。
        if (chordPlayer == null) chordPlayer = new ChordPlayer(this);
        return chordPlayer;
    }

    private void playChord(ChordLibrary.Chord chord) {
        if (chord == null) return;
        tap();
        if (!ensureChordPlayer().play(chord)) { toast("手机音频输出不可用，无法试听"); return; }
        if (funStore != null) {
            funStore.recordChordPlayed(chord.name);
            if (funStore.chordsPlayed() >= 8) unlockBadge(ACH_CHORD_EXPLORER);
        }
        toast("和弦 " + chord.name + (chord.displayName == null ? "" : " · " + chord.displayName));
    }

    // ---- 音乐小问答 ----

    private void newQuizQuestion() {
        quizQuestion = MusicTrivia.random(funRandom);
        quizAnswered = false;
        if (quizQuestionLabel != null) quizQuestionLabel.setText(quizQuestion.question);
        if (quizFeedbackLabel != null) quizFeedbackLabel.setText("");
        for (int i = 0; i < quizOptionButtons.length; ++i) {
            TextView option = quizOptionButtons[i];
            if (option == null) continue;
            if (quizQuestion.options != null && i < quizQuestion.options.length) option.setText(quizQuestion.options[i]);
            option.setBackground(kit.ripple(cSurface2(), 14));
            option.setTextColor(cTextPrimary());
            option.setEnabled(true);
            option.setAlpha(1f);
        }
        if (quizStreakLabel != null && funStore != null) quizStreakLabel.setText("连对 " + funStore.quizStreak());
    }

    private void answerQuiz(int index) {
        if (quizQuestion == null || quizAnswered) return;
        quizAnswered = true;
        tap();
        boolean correct = index == quizQuestion.answer;
        if (funStore != null) funStore.recordQuiz(correct);
        for (int i = 0; i < quizOptionButtons.length; ++i) {
            TextView option = quizOptionButtons[i];
            if (option == null) continue;
            option.setEnabled(false);
            if (i == quizQuestion.answer) {
                option.setBackground(kit.bg(cAccentDim(), 14));
                option.setTextColor(cOnAccentDim());
            } else if (i == index && !correct) {
                option.setBackground(kit.bg(AppTheme.withAlpha(cDanger(), 0.16f), 14));
                option.setTextColor(cDanger());
            } else {
                option.setBackground(kit.bg(cSurface2(), 14));
                option.setTextColor(cTextTertiary());
            }
        }
        if (quizFeedbackLabel != null) quizFeedbackLabel.setText((correct ? "答对了！" : "再想想～") + (quizQuestion.explain == null ? "" : quizQuestion.explain));
        if (quizStreakLabel != null && funStore != null) quizStreakLabel.setText("连对 " + funStore.quizStreak());
        if (correct) {
            if (feedback != null) feedback.confirm();
            if (funStore != null && funStore.quizStreak() >= 5) unlockBadge(ACH_QUIZ_ACE);
        } else if (feedback != null) {
            feedback.warn();
        }
    }

    // ---- 启动加载动画 ----

    private void startSplashAnimation() {
        if (appShell == null) return;
        setSplashWindowBars();
        splashOverlay = new SplashOverlay(this);
        appShell.addView(splashOverlay, new FrameLayout.LayoutParams(-1, -1));
        splashOverlay.start("Ukulele Studio", "v" + AppVersion.NAME, MusicTrivia.tips(), () -> {
            if (isFinishing() || isDestroyed()) return;
            SplashOverlay finished = splashOverlay;
            splashOverlay = null;
            if (finished != null && appShell != null) appShell.removeView(finished);
            applyWindowTheme();
            Runnable pending = pendingAfterSplash;
            pendingAfterSplash = null;
            if (pending != null) pending.run();
        });
    }

    private void setSplashWindowBars() {
        android.view.Window window=getWindow();
        int background=AppTheme.darkColors().bgRoot;
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        View decor=window.getDecorView();
        int flags=decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)flags&=~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(flags);
    }

    /** 需要在启动动画结束后再执行的动作（如首次欢迎弹窗）；没有动画时立即执行。 */
    private void runAfterSplash(Runnable action) {
        if (action == null) return;
        if (splashOverlay != null && splashOverlay.isRunning()) pendingAfterSplash = action;
        else action.run();
    }

    /** 重建界面/退出时安全地拆掉启动动画，避免它继续持有回调。 */
    private void dismissSplash() {
        if (splashOverlay != null) {
            splashOverlay.cancel();
            SplashOverlay finished = splashOverlay;
            splashOverlay = null;
            if (finished != null && appShell != null) appShell.removeView(finished);
            applyWindowTheme();
        }
        pendingAfterSplash = null;
    }

    // ---- 彩蛋 ----

    private void showEasterEgg() {
        tap();
        unlockBadge(ACH_EGG_HUNTER);
        showConfetti();
        String[] eggs = {
            "彩蛋：尤克里里只有 4 根弦，却装得下一整支乐队。",
            "彩蛋：高音 G 弦让尤克里里的声音更亮，也更「叮」。",
            "彩蛋：每天 10 分钟，比周末狂练 2 小时更容易记住。",
            "彩蛋：C、Am、F、G 四个和弦能弹无数首流行歌。"
        };
        toast(eggs[funRandom.nextInt(eggs.length)]);
    }

    private void showConfetti() {
        if (appShell == null) return;
        if (confettiOverlay != null) { appShell.removeView(confettiOverlay); confettiOverlay = null; }
        final ConfettiView view = new ConfettiView(this);
        confettiOverlay = view;
        appShell.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.burst();
        main.postDelayed(() -> {
            if (confettiOverlay == view) {
                confettiOverlay = null;
                if (appShell != null) appShell.removeView(view);
            }
        }, 1700);
    }

    // =====================================================================
    // v3.12.0：DeepSeek AI 集成（配置 / 音乐搭子 / AI 曲谱导入 / 练习点评 / 灯效导演）
    //
    // 设计边界：AI 只做「建议与内容生成」，不接管任何设备控制或曲谱上传流程。
    // AI 生成的事件文本必须经过本页既有 normalizeTextSong + SongTimeline.parse 校验，
    // 合法后才走原来的 selectImportedSong 导入；非法就拒绝并提示，避免把模型输出直接当数据。
    // =====================================================================

    /** AI 是否可用：已选择通道且能取到 Key（官方通道未内置 Key 时视为不可用）。 */
    private boolean aiReady() {
        if (aiCredentials == null) return false;
        if (aiCredentials.mode() == AiCredentials.MODE_OFF) return false;
        String key = aiCredentials.activeKey();
        return key != null && !key.trim().isEmpty();
    }

    /** 复用一个 DeepSeekClient；配置变更后关闭旧实例并重建。 */
    private DeepSeekClient aiClient() {
        if (cachedAiClient == null) {
            cachedAiClient = new DeepSeekClient(aiCredentials.activeKey(), aiCredentials.baseUrl(), aiCredentials.model());
        } else {
            cachedAiClient.setApiKey(aiCredentials.activeKey());
            cachedAiClient.setBaseUrl(aiCredentials.baseUrl());
            cachedAiClient.setModel(aiCredentials.model());
        }
        return cachedAiClient;
    }

    /** 刷新设置页/乐趣页上的 AI 状态文案（页面未构建时静默）。 */
    private void refreshAiUi() {
        if (aiCredentials == null) return;
        boolean ready = aiReady();
        int mode = aiCredentials.mode();
        String model = aiCredentials.model();
        String status;
        if (mode == AiCredentials.MODE_OFF) {
            status = "AI 未启用 · 点上方按钮配置个人 Key 或官方通道";
        } else if (mode == AiCredentials.MODE_PERSONAL) {
            status = ready ? ("已启用 · 个人 API Key · " + model) : "个人 Key 未保存完整，请重新配置";
        } else {
            status = ready ? ("已启用 · 官方通道 · " + model)
                : "官方通道未配置密钥（安装包未注入开发者 Key），请改用个人 Key 或联系开发者";
        }
        if (aiStatusLabel != null) {
            aiStatusLabel.setText(status);
            aiStatusLabel.setTextColor(ready ? cAccent() : cTextSecondary());
        }
        if (aiFunStatusLabel != null) {
            aiFunStatusLabel.setText(status);
        }
        if (aiFunPersonaLabel != null && companionStore != null) {
            CompanionPersona persona = companionStore.persona();
            if (persona != null) aiFunPersonaLabel.setText(persona.avatar + " " + persona.name);
        }
    }

    /** 打开 AI 配置。onChanged 在保存/关闭后执行，用于刷新状态或继续打开聊天。 */
    private void openAiSetup(Runnable after) {
        AiSetupDialog.show(this, () -> {
            if (cachedAiClient != null) {
                cachedAiClient.shutdown();
                cachedAiClient = null;
            }
            refreshAiUi();
            if (after != null) after.run();
        });
    }

    /** 打开前确保已配置；未配置先弹配置，配置成功后自动继续。 */
    private void openAiSetupOrContinue(Runnable action) {
        if (aiReady()) {
            if (action != null) action.run();
        } else {
            openAiSetup(() -> { if (aiReady() && action != null) action.run(); });
        }
    }

    private void openAiChat() {
        openAiSetupOrContinue(this::showAiChatNow);
    }

    private void showAiChatNow() {
        if (companionStore == null) companionStore = new CompanionStore(this);
        if (isFinishing() || isDestroyed()) return;
        // 不在打开聊天时预申请麦克风权限：语音默认关闭，等用户真的点麦克风再申请，
        // 避免“刚打开聊天就弹权限”的突兀体验（复核 P2-1）。权限与录音由聊天弹窗自行管理。
        AiChatDialog.show(this, aiCredentials, companionStore, buildAiAppContext(),
            (type, label, param) -> handleAiAction(type, label, param));
        main.postDelayed(() -> {
            refreshAiUi();
            if (companionStore != null) companionStore.savePersona(companionStore.persona());
        }, 600);
    }

    /** 把当前 App 状态压缩成一段给 AI 的上下文；不包含任何隐私或 API Key。 */
    private String buildAiAppContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("设备：").append(bleReady ? "已连接" : "未连接");
        sb.append("；当前曲目：").append(selectedSongName == null ? "未选择" : selectedSongName);
        if (funStore != null) sb.append("；连续打卡：").append(funStore.streak()).append(" 天");
        if(journey!=null){PracticeJourneyStore.Snapshot j=journey.snapshot();sb.append("；今日真实练习完成").append(j.runs).append("次，命中").append(j.correct).append("音，今日最高").append(j.best/100.0).append("分；请按这些数据建议下一步，不要编造演奏表现");}
        if (practiceScorer != null) {
            try {
                PracticeScorer.Snapshot s = practiceScorer.snapshot();
                if (s != null && s.total > 0) sb.append("；最近评分：").append(gradeText(s.hundredths())).append(" 分");
            } catch (Throwable ignored) { }
        }
        if (selectedTimeline != null) sb.append("；曲目时长：").append(formatSongTime(selectedTimeline.durationMs));
        return sb.toString();
    }

    /** AI 回复里的行为标签：只映射到既有页面/提示，绝不直接执行网络或设备写操作。 */
    private void handleAiAction(String type, String label, String param) {
        String t = type == null ? "" : type.toUpperCase(java.util.Locale.ROOT);
        switch (t) {
            case "PRACTICE": showPage(3); break;
            case "LED": showPage(1); break;
            case "METRONOME": showPage(PAGE_FUN); toast("节拍器在「乐趣」页"); break;
            case "SCORE": showPage(2); break;
            case "PLAY": showPage(2); break;
            case "TIP":
            default:
                if (param != null && !param.trim().isEmpty()) toast(param);
                else if (label != null && !label.trim().isEmpty()) toast(label);
                break;
        }
    }

    private void openAiScoreImport() {
            if (isFinishing() || isDestroyed()) return;
            AiScoreImportDialog.show(this, aiCredentials, new AiScoreImportDialog.ImportHandler() {
                @Override public String describe(String eventsText) {
                    return aiDescribeEvents(eventsText);
                }
                @Override public void onImport(String title, String eventsText) {
                    aiImportSong(title, eventsText);
                }
                @Override public void onImportMidi(byte[] midiBytes, String title) {
                    aiImportMidiSong(midiBytes, title);
                }
                @Override public void requestImage(AiScoreImportDialog.ImageCallback callback) {
                    requestAiImage(callback);
                }
                @Override public void requestDocument(AiScoreImportDialog.DocumentCallback callback){requestAiDocument(callback);}
                @Override public void requestScoreFile(Runnable selected){
                    pendingBrowserScoreSelected=selected;
                    Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/midi","audio/x-midi","application/x-midi","application/octet-stream","application/pdf","image/*","text/plain"});
                    try{startActivityForResult(intent,REQ_BROWSER_SCORE);}
                    catch(Exception error){pendingBrowserScoreSelected=null;toast("没有可用的文件选择器");}
                }
                @Override public String describeMidi(byte[] bytes){
                    try{PreviewScore score=PreviewScore.fromMidi(bytes);return score.notes.size()+"个原始MIDI音符 · "+formatSongTime((long)(score.duration*1000))+" · 导入时检查四弦音域";}
                    catch(Exception error){return null;}
                }
                @Override public void importReviewed(AiScoreAssistant.ScoreDraft draft,ScoreImageDocument document,AiScoreImportDialog.ImportResult done){aiImportReviewed(draft,document,done);}

                @Override public void previewText(String eventsText, String title) {
                    aiPreviewText(eventsText, title);
                }
                @Override public void previewMidi(byte[] midiBytes, String title) {
                    aiPreviewMidi(midiBytes, title);
                }
                @Override public void setPreviewObserver(AiScoreImportDialog.PreviewObserver observer){
                    aiPreviewObserver=observer;
                    if(observer!=null)observer.changed(aiPreviewSession.isActive(),aiPreviewSession.isActive()?"正在试听":"点击试听，可随时停止");
                }
                @Override public void stopPreview() {
                    aiStopCandidatePreview();
                }
            });
    }

    /** 用既有解析器验证 AI 生成的事件文本，返回可读摘要；null 表示不可导入。 */
    private String aiDescribeEvents(String eventsText) {
        if (eventsText == null || eventsText.trim().isEmpty()) return null;
        try {
            byte[] events = normalizeTextSong(eventsText);
            if (events == null || events.length < 8) return null;
            SongTimeline timeline = SongTimeline.parse(events);
            if (timeline == null || timeline.noteCount <= 0) return null;
            return "可导入 " + timeline.noteCount + " 个弦位音符 · " + formatSongTime(timeline.durationMs);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** AI 事件合法才导入：复用既有 selectImportedSong（写入我的曲库并选中）。 */
    private void aiImportSong(String title, String eventsText) {
        aiStopCandidatePreview();
        try {
            byte[] events = normalizeTextSong(eventsText);
            if (events == null || events.length < 8) { toast("AI 生成的事件无法解析，请编辑后重试"); return; }
            SongTimeline.parse(events);
            String name = title == null || title.trim().isEmpty() ? "AI 导入曲谱" : title.trim();
            selectImportedSong(events, name);
            toast("已导入：" + name);
            showSongSection(true);
        } catch (Exception error) {
            toast("导入失败：" + (error.getMessage() == null ? "事件格式不可用" : error.getMessage()));
        }
    }

    /** AI 搜到的 MIDI 直接走 App 既有的 MIDI→UKS 解析器，避免让模型编造音符。 */
    private void aiImportMidiSong(byte[] midiBytes, String title) {
        aiStopCandidatePreview();
        if (midiBytes == null || midiBytes.length == 0) { toast("MIDI 数据为空，请重试或换一首"); return; }
        final String name=title==null||title.trim().isEmpty()?"AI 搜索的 MIDI 曲谱":title.trim();
        scoreWorker.execute(()->{
            try{
                ScorePitch.Result result=MidiGuide.convert(midiBytes);
                ScoreRepository.Item item=scoreRepo.importFile(name,"audio/midi",midiBytes);
                scoreRepo.setEvents(item,result.events,result.warning(),result.outside+result.overflow);
                main.post(()->{
                    if(isDestroyed())return;
                    rebuildSavedScores();
                    MidiOctaveEditor.show(this,scoreRepo,item.id,()->{rebuildSavedScores();selectSavedScore(item.id);});
                });
            }catch(Exception error){main.post(()->toast("MIDI 导入失败："+(error.getMessage()==null?"格式不可用":error.getMessage())));}
        });
    }

    /** Parsing happens on the player's worker, never on the UI thread. */
    private void aiPreviewText(String eventsText,String title){
        aiStartCandidatePreview(()->{
            if(eventsText==null||eventsText.trim().isEmpty())throw new IllegalArgumentException("没有可试听的内容");
            byte[] events=normalizeTextSong(eventsText);
            if(events==null||events.length<8)throw new IllegalArgumentException("曲谱事件无法解析");
            SongTimeline timeline=SongTimeline.parse(events);
            if(timeline.noteCount<=0)throw new IllegalArgumentException("没有可试听音符");
            return PreviewScore.fromTimeline(timeline);
        },title);
    }
    private void aiPreviewMidi(byte[] bytes,String title){
        aiStartCandidatePreview(()->PreviewScore.fromMidi(bytes),title);
    }
    private void aiPublishPreview(boolean active,String message){
        if(aiPreviewObserver!=null&&!isDestroyed())aiPreviewObserver.changed(active,message);
    }
    private void aiCancelPreviewDeadline(){
        if(aiCandidatePreviewStop!=null){main.removeCallbacks(aiCandidatePreviewStop);aiCandidatePreviewStop=null;}
    }
    private void aiStartCandidatePreview(ScorePreviewPlayer.Source source,String title){
        aiStopCandidatePreview();
        if(isDestroyed())return;
        if(auditionPlayer!=null&&auditionPlayer.isActive())auditionPlayer.stop();
        final long token=aiPreviewSession.begin();aiPlaybackToken=token;
        if(aiCandidatePreview==null){
            aiCandidatePreview=new ScorePreviewPlayer(this,new ScorePreviewPlayer.Listener(){
                public void state(String message){
                    if(aiPreviewStarting)return;
                    boolean playing=aiCandidatePreview!=null&&aiCandidatePreview.isActive();
                    if(!playing){aiPreviewSession.finish(aiPlaybackToken);aiCancelPreviewDeadline();}
                    aiPublishPreview(playing,message);
                }
                public void ready(long duration){
                    final long ticket=aiPlaybackToken;
                    if(!aiPreviewSession.isCurrent(ticket))return;
                    aiCancelPreviewDeadline();
                    aiCandidatePreviewStop=()->{
                        if(!aiPreviewSession.isCurrent(ticket))return;
                        aiStopCandidatePreview();aiPublishPreview(false,"本次试听结束 · 可重新试听或应用");
                    };
                    main.postDelayed(aiCandidatePreviewStop,12000);
                }
            });
        }
        aiCandidatePreview.setVolume(.9f);
        aiPublishPreview(true,"准备试听…");
        aiPreviewStarting=true;
        try{aiCandidatePreview.start(source,1,title==null||title.trim().isEmpty()?"当前曲谱":title.trim());}
        catch(Exception error){aiPreviewSession.finish(token);aiCancelPreviewDeadline();aiPublishPreview(false,"无法试听，请重试");}
        finally{aiPreviewStarting=false;}
        // Focus rejection can finish synchronously while start notifications are suppressed.
        if(!aiCandidatePreview.isActive()&&aiPreviewSession.finish(token))aiPublishPreview(false,"暂时无法播放，请稍后再试；仍可核对并应用");
    }
    private void aiStopCandidatePreview(){
        aiPreviewSession.stop();aiCancelPreviewDeadline();
        aiPreviewStarting=true;
        try{if(aiCandidatePreview!=null&&aiCandidatePreview.isActive())aiCandidatePreview.stop();}
        finally{aiPreviewStarting=false;}
        aiPublishPreview(false,"已停止试听 · 可核对并应用");
    }

    private void requestAiDocument(AiScoreImportDialog.DocumentCallback callback){
        pendingAiDocumentCallback=callback;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","application/pdf"});intent.addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(intent,REQ_AI_DOCUMENT);}catch(Exception e){pendingAiDocumentCallback=null;callback.error("没有可用的文件选择器");}
    }
    private void handleAiDocumentResult(int resultCode,Intent data){
        final AiScoreImportDialog.DocumentCallback callback=pendingAiDocumentCallback;
        if(callback==null)return;
        if(resultCode!=RESULT_OK||data==null||data.getData()==null){pendingAiDocumentCallback=null;return;}
        final Uri uri=data.getData();final String name=displayName(uri);
        scoreWorker.execute(()->{
            try{
                final byte[] original;try(InputStream in=getContentResolver().openInputStream(uri)){original=ScoreRepository.read(in);}
                final String type=getContentResolver().getType(uri);final int pages=ScoreImageReader.isPdf(original)?ScoreImageReader.pdfPages(this,original):1;
                main.post(()->{
                    if(isDestroyed()||pendingAiDocumentCallback!=callback)return;
                    if(pages==1){renderAiDocument(callback,original,type,name,1,1);return;}
                    LinearLayout form=column();form.addView(hint("共"+pages+"页，每批选择连续1至6页；只上传所选页，原PDF完整保留。"));
                    final EditText from=new EditText(this),to=new EditText(this);from.setHint("起始页");to.setHint("结束页");from.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);to.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                    AppDialogs.themeInput(from,15,false);AppDialogs.themeInput(to,15,false);from.setText("1");to.setText(String.valueOf(Math.min(6,pages)));
                    form.addView(from);form.addView(to);
                    final android.app.AlertDialog[] picker={null};final boolean[] started={false};
                    TextView go=primaryAction("开始逐页识别");LinearLayout.LayoutParams goLp=new LinearLayout.LayoutParams(-1,-2);goLp.topMargin=dp(14);form.addView(go,goLp);
                    go.setOnClickListener(v->{
                        try{int a=Integer.parseInt(from.getText().toString()),b=Integer.parseInt(to.getText().toString());if(a<1||b<a||b>pages||b-a+1>6)throw new IllegalArgumentException();
                            started[0]=true;picker[0].dismiss();renderAiDocument(callback,original,type,name,a,b);
                        }catch(Exception e){toast("请输入有效的连续页码，每批最多6页");}
                    });
                    picker[0]=AppDialogs.form(this,"选择识别页码",form,java.util.Collections.singletonList(new AppDialogs.Action("取消",null,false)),20);
                    picker[0].setOnDismissListener(d->{if(!started[0]&&pendingAiDocumentCallback==callback)pendingAiDocumentCallback=null;});

                });
            }catch(Exception e){main.post(()->{if(!isDestroyed()&&pendingAiDocumentCallback==callback){pendingAiDocumentCallback=null;callback.error("无法读取谱面："+e.getMessage());}});}
        });
    }
    private void renderAiDocument(AiScoreImportDialog.DocumentCallback callback,byte[] original,String type,String name,int first,int last){
        toast("正在准备谱面…");
        scoreWorker.execute(()->{
            try{final ScoreImageDocument doc=ScoreImageReader.read(this,original,type,name,first,last);
                main.post(()->{if(!isDestroyed()&&pendingAiDocumentCallback==callback){pendingAiDocumentCallback=null;callback.onDocument(doc);}});
            }catch(Exception e){main.post(()->{if(!isDestroyed()&&pendingAiDocumentCallback==callback){pendingAiDocumentCallback=null;callback.error("谱面准备失败："+e.getMessage());}});}
        });
    }
    private void aiImportReviewed(AiScoreAssistant.ScoreDraft draft,ScoreImageDocument document,AiScoreImportDialog.ImportResult done){
        aiStopCandidatePreview();
        scoreWorker.execute(()->{
            try{
                byte[] events;byte[] original;String mime;int omitted=draft.omittedNotes;
                if(draft.midiBytes!=null){int before=midiSkipped;try{midiSkipped=0;events=normalizeSong(draft.midiBytes);omitted=midiSkipped;}finally{midiSkipped=before;}original=draft.midiBytes;mime="audio/midi";}
                else{events=normalizeTextSong(draft.eventsText);original=draft.transcription.getBytes(java.nio.charset.StandardCharsets.UTF_8);mime="text/plain";}
                if(events==null||SongTimeline.parse(events).noteCount<=0)throw new java.io.IOException("没有可练习音符");
                if(document!=null&&document.original!=null){original=document.original;mime=document.mime;}
                final ScoreRepository.Item item=scoreRepo.importFile(draft.title,mime,original);
                item.type=document==null?"AI导入":"AI逐页转录";
                String warning=(draft.deterministic?"规则解析":"AI转录已校对")+(omitted>0?" · 超音域"+omitted+"音":"")+(document==null?"":" · "+document.pageLabel());
                scoreRepo.setEvents(item,events,warning,omitted);
                ScoreRepository.atomic(new java.io.File(scoreRepo.folder(item.id),"ai_transcription.txt"),draft.transcription.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                org.json.JSONObject provenance=new org.json.JSONObject().put("version",1).put("source",draft.source).put("url",draft.sourceUrl).put("originalName",document==null?draft.title:document.name).put("firstPage",document==null?1:document.firstPage).put("selectedPages",document==null?1:document.pages.size()).put("totalPages",document==null?1:document.totalPages).put("omittedNotes",omitted);
                ScoreRepository.atomic(new java.io.File(scoreRepo.folder(item.id),"ai_import.json"),provenance.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                main.post(()->{if(isDestroyed())return;rebuildSavedScores();selectSavedScore(item.id);showSongSection(true);done.completed(null);});
            }catch(Exception error){main.post(()->{if(!isDestroyed())done.completed(error.getMessage()==null?"存储失败":error.getMessage());});}
        });
    }

    private void requestAiImage(AiScoreImportDialog.ImageCallback callback) {        pendingAiImageCallback = callback;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQ_AI_IMAGE);
        } catch (Exception error) {
            pendingAiImageCallback = null;
            toast("没有可用的图片选择器");
        }
    }

    private void handleAiImageResult(int resultCode, Intent data) {
        final AiScoreImportDialog.ImageCallback callback = pendingAiImageCallback;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingAiImageCallback = null;
            return;
        }
        final Uri uri = data.getData();
        final String name = displayName(uri);
        scoreWorker.execute(() -> {
            byte[] jpeg = null;
            String failure = null;
            try {
                jpeg = decodeAiImageBytes(uri);
                if (jpeg == null || jpeg.length == 0) failure = "图片读取失败";
            } catch (Throwable error) {
                failure = "图片读取失败：" + (error.getMessage() == null ? "无法解码" : error.getMessage());
            }
            final byte[] bytes = jpeg;
            final String reason = failure;
            main.post(() -> {
                AiScoreImportDialog.ImageCallback cb = pendingAiImageCallback;
                pendingAiImageCallback = null;
                if (callback != cb) return;
                if (bytes == null) toast(reason == null ? "图片读取失败" : reason);
                else if (cb != null) cb.onImage(bytes, "image/jpeg", name);
            });
        });
    }

    /** 相册图片转 JPEG：限制长边 1600px，控制 base64 体积与视觉识别 token 成本。 */
    private byte[] decodeAiImageBytes(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        final int maxSide = 1600;
        int sample = 1;
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) return null;
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest > maxSide) {
            float scale = maxSide / (float) longest;
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * scale)),
                Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    /** 让音乐搭子点评最近一轮评分练习；AI 不可用时先引导配置。 */
    private void aiReviewPractice() {
        openAiSetupOrContinue(() -> {
            if (practiceScorer == null) { toast("先完成一轮评分练习，再来让搭子点评"); return; }
            PracticeScorer.Snapshot snapshot;
            try {
                snapshot = practiceScorer.snapshot();
            } catch (Throwable error) {
                snapshot = null;
            }
            if (snapshot == null || snapshot.total <= 0) { toast("这轮还没有可点评的记录"); return; }
            if (companionStore == null) companionStore = new CompanionStore(this);
            CompanionPersona persona = companionStore.persona();
            CompanionEngine engine = new CompanionEngine(aiClient(), persona, companionStore);
            String song = selectedSongName == null ? "当前曲目" : selectedSongName;
            toast("搭子正在看你的练习…");
            final PracticeScorer.Snapshot snap = snapshot;
            engine.reviewPractice(song, snap.hundredths(), snap.correct, snap.wrong, snap.missed,
                buildAiAppContext(), new CompanionEngine.ReplyCallback() {
                    @Override public void onSuccess(CompanionEngine.Reply reply) {
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            AppDialogs.message(MainActivity.this, "AI 搭子点评",
                                reply == null || reply.text == null ? "搭子走神了，再试一次吧。" : reply.text,
                                "知道啦", null, null, null, null, null);
                        });
                    }
                    @Override public void onError(DeepSeekClient.AiError error) {
                        runOnUiThread(() -> toast("点评失败：" + (error == null || error.message == null ? "网络异常" : error.message)));
                    }
                });
        });
    }

    /** 一句话描述氛围 → DeepSeek 给出灯效参数 → 复用既有场景应用逻辑。 */
    private void openAiLightDirector(){com.ukulele.monitor.ui.AppDialogs.builder(this).setTitle("AI 灯光设计").setItems(new String[]{"72灯独立配置 · 区域亮度与呼吸","系统灯效 · 呼吸、游龙与拨弦跟随"},(d,w)->{if(w==0)openAiPixelProfile();else openAiBuiltInLightDirector();}).show();}
    private void openAiBuiltInLightDirector() {

        openAiSetupOrContinue(() -> {

            final EditText input = new EditText(this);

            input.setHint("例如：夏夜湖边一样安静地呼吸 / 热烈的舞台快闪 / 像星星慢慢流动");

            AppDialogs.themeInput(input, 14, false);

            LinearLayout form = column();

            form.addView(input, new LinearLayout.LayoutParams(-1, dp(50)));

            AppDialogs.form(this, "AI 灯效导演", form, java.util.Collections.singletonList(

                new AppDialogs.Action("生成并应用", (dialog, which) -> {

                    String wish = input.getText().toString().trim();

                    if (wish.isEmpty()) { toast("先描述一下你想要的灯效氛围"); return; }

                    toast("AI 正在设计灯效…");

                    new LightDirector(aiClient()).suggest(wish, new LightDirector.Cb() {

                        @Override public void onSuccess(LightDirector.LightPlan plan) {

                            runOnUiThread(() -> {

                                if (plan == null) { toast("AI 没有给出可用的灯效参数"); return; }

                                applyLedScene(new int[]{plan.mode, plan.axis, plan.speed, plan.brightness});

                                if (plan.reason != null && !plan.reason.trim().isEmpty()) toast("AI：" + plan.reason);

                            });

                        }

                        @Override public void onError(DeepSeekClient.AiError error) {

                            runOnUiThread(() -> toast("AI 灯效失败："

                                + (error == null || error.message == null ? "网络异常" : error.message)));

                        }

                    });

                }, true)), 20);

        });

    }




    private int bitmapAiRequest;
    private void openAiPixelProfile(){
        openAiSetupOrContinue(()->{
            EditText input=new EditText(this);input.setHint("例如：星空伴随流星，或者生成GOWIN字样");AppDialogs.themeInput(input,14,false);
            LinearLayout form=column();form.addView(input,new LinearLayout.LayoutParams(-1,dp(70)));
            AppDialogs.form(this,"AI 灯光设计",form,java.util.Collections.singletonList(new AppDialogs.Action("生成配置预览",(dialog,which)->{
                String wish=input.getText().toString().trim();if(wish.isEmpty()){toast("请描述想要的灯光");return;}toast("AI正在设计72灯配置…");openBitmapEditor();bitmapEditor.setAiBusy(true);final int request=++bitmapAiRequest;final LedProfile before=bitmapEditor.profile();
                requestBitmapAi(wish,before,request,0,"");
            },true)),20);
        });
    }

    private void requestBitmapAi(String wish,LedProfile before,int request,int attempt,String repair){
        String prompt=wish+"\n"+before.aiContext()+repair;
        aiClient().chat(LedProfile.AI_PROMPT,java.util.Collections.singletonList(DeepSeekClient.ChatMessage.user(prompt)),true,3000,false,.15f,new DeepSeekClient.ChatCallback(){
            public void onSuccess(String text){
                try{LedProfile.Plan plan=LedDesignCompiler.finish(wish,text,before);runOnUiThread(()->{if(request!=bitmapAiRequest||isFinishing())return;openBitmapEditor();bitmapEditor.setAiBusy(false);updateBitmapUi();bitmapEditor.importAi(plan);});}
                catch(IllegalArgumentException error){runOnUiThread(()->{if(request!=bitmapAiRequest||isFinishing())return;if(attempt==0){bitmapEditor.aiButton.setText("正在校正…");requestBitmapAi(wish,before,request,1,"\n上次配置校验失败："+error.getMessage()+"。重新生成严格字面量JSON，不能出现+、*算式。不要执行代码，不得省略任何数组值。");}else{bitmapEditor.setAiBusy(false);toast("AI配置仍需调整："+error.getMessage());}});}
            }
            public void onError(DeepSeekClient.AiError error){runOnUiThread(()->{if(request!=bitmapAiRequest||isFinishing())return;bitmapEditor.setAiBusy(false);toast("AI灯光生成失败："+(error==null?"网络异常":error.message));});}
        });
    }

    private boolean hasRecordAudioPermission() {        try {
            return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 语音开启时先请求麦克风权限；拒绝也继续（文字聊天不受影响）。 */
    private void ensureVoicePermissionThen(Runnable action) {
        if (action == null) return;
        if (aiCredentials == null || !aiCredentials.voiceEnabled() || hasRecordAudioPermission()) {
            action.run();
            return;
        }
        pendingAiVoiceGranted = action;
        try {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AI_VOICE_PERM);
        } catch (Throwable error) {
            pendingAiVoiceGranted = null;
            action.run();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AI_VOICE_PERM) {
            Runnable pending = pendingAiVoiceGranted;
            pendingAiVoiceGranted = null;
            boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) toast("没有麦克风权限，仍可用文字和搭子聊天");
            if (pending != null) pending.run();
        }
    }

}
