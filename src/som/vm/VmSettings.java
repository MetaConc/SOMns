package som.vm;

import java.util.Arrays;
import java.util.List;

public class VmSettings {
  public static final int NUM_THREADS;


  // TODO: revise naming of flags
  public static final boolean FAIL_ON_MISSING_OPTIMIZATIONS;
  public static final boolean DEBUG_MODE;
  public static boolean actorTracing;
  public static boolean memoryTracing;
  public static final String  TRACE_FILE;
  public static final boolean DISABLE_TRACE_FILE;
  public static final boolean INSTRUMENTATION;
  public static final boolean DYNAMIC_METRICS;
  public static final boolean DNU_PRINT_STACK_TRACE;
  public static final boolean MESSAGE_PARAMETERS;
  public static boolean promiseCreation;
  public static boolean promiseResolution;
  public static boolean promiseResolvedWith;
  public static final boolean REPLAY;
  public static boolean timeTravelling; // is this is time travel run
  public static boolean timeTravellingRecording; // is SOM currently doing the in initial run

  public static final boolean TRUFFLE_DEBUGGER_ENABLED;

  public static final boolean IGV_DUMP_AFTER_PARSING;

  public static final String INSTRUMENTATION_PROP = "som.instrumentation";

  static {
    String prop = System.getProperty("som.threads");
    if (prop == null) {
      NUM_THREADS = Runtime.getRuntime().availableProcessors();
    } else {
      NUM_THREADS = Integer.valueOf(prop);
    }

    FAIL_ON_MISSING_OPTIMIZATIONS = getBool("som.failOnMissingOptimization", false);
    DEBUG_MODE      = getBool("som.debugMode",      false);
    TRUFFLE_DEBUGGER_ENABLED = getBool("som.truffleDebugger", false);

    TRACE_FILE      = System.getProperty("som.traceFile", System.getProperty("user.dir") + "/traces/trace");
    memoryTracing = getBool("som.memoryTracing",   false);
    REPLAY = getBool("som.replay", false);
    DISABLE_TRACE_FILE = getBool("som.disableTraceFile", false) || REPLAY;
    timeTravelling = getBool("som.time_travel", false);
    timeTravellingRecording = timeTravelling;

    String atConfig = System.getProperty("som.actorTracingCfg", "");
    List<String> al = Arrays.asList(atConfig.split(":"));
    boolean filter = (al.size() > 0 && !atConfig.isEmpty()) || getBool("som.actorTracing",   false);

    MESSAGE_PARAMETERS    = !al.contains("mp") && filter;
    promiseCreation      = !al.contains("pc") && filter || timeTravelling;
    promiseResolution    = promiseCreation && (!al.contains("pr")) && filter || timeTravelling;
    promiseResolvedWith = !al.contains("prw") && filter || timeTravelling;


    actorTracing = TRUFFLE_DEBUGGER_ENABLED || getBool("som.actorTracing", false) ||
                    REPLAY || MESSAGE_PARAMETERS || promiseCreation || timeTravelling;

    boolean dm = getBool("som.dynamicMetrics", false);
    DYNAMIC_METRICS = dm;
    INSTRUMENTATION = dm || getBool(INSTRUMENTATION_PROP, false);

    DNU_PRINT_STACK_TRACE = getBool("som.printStackTraceOnDNU", false);

    IGV_DUMP_AFTER_PARSING = getBool("som.igvDumpAfterParsing", false);
  }

  private static boolean getBool(final String prop, final boolean defaultVal) {
    return Boolean.parseBoolean(System.getProperty(prop, defaultVal ? "true" : "false"));
  }
}
