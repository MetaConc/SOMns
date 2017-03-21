package som;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.instrumentation.InstrumentableFactory.WrapperNode;
import com.oracle.truffle.api.instrumentation.InstrumentationHandler;
import com.oracle.truffle.api.nodes.GraphPrintVisitor;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.vm.PolyglotEngine;
import com.oracle.truffle.api.vm.PolyglotEngine.Builder;
import com.oracle.truffle.api.vm.PolyglotEngine.Instrument;
import com.oracle.truffle.tools.Profiler;
import com.oracle.truffle.tools.ProfilerInstrument;

import coveralls.truffle.Coverage;
import som.compiler.MixinDefinition;
import som.compiler.SourcecodeCompiler;
import som.interpreter.Method;
import som.interpreter.SomLanguage;
import som.interpreter.TruffleCompiler;
import som.interpreter.actors.Actor;
import som.interpreter.actors.SFarReference;
import som.interpreter.actors.SPromise;
import som.interpreter.actors.SPromise.SResolver;
import som.primitives.processes.ChannelPrimitives;
import som.primitives.threading.ThreadingModule;
import som.vm.ObjectSystem;
import som.vm.Primitives;
import som.vm.VmOptions;
import som.vm.VmSettings;
import som.vm.constants.KernelObj;
import som.vmobjects.SObjectWithClass.SObjectWithoutFields;
import tools.concurrency.ActorExecutionTrace;
import tools.concurrency.TracingActors;
import tools.concurrency.Assertion.FutureAssertion;
import tools.debugger.Tags;
import tools.debugger.WebDebugger;
import tools.dym.DynamicMetrics;
import tools.language.StructuralProbe;


public final class VM {

  @CompilationFinal private static PolyglotEngine engine;
  @CompilationFinal private static VM vm;
  @CompilationFinal private static StructuralProbe structuralProbe;
  @CompilationFinal private static WebDebugger webDebugger;
  private static Profiler truffleProfiler;

  public static WebDebugger getWebDebugger() {
    return webDebugger;
  }

  private final Map<String, Object> exports = new HashMap<>();

  public boolean registerExport(final String name, final Object value) {
    boolean wasExportedAlready = exports.containsKey(name);
    exports.put(name, value);
    return wasExportedAlready;
  }

  public Object getExport(final String name) {
    return exports.get(name);
  }

  /**
   * @return last VM instance, for tests only
   */
  public static VM getVM() {
    return vm;
  }

  public static void setEngine(final PolyglotEngine e) {
    engine = e;
  }

  private final boolean avoidExitForTesting;
  private final ObjectSystem objectSystem;

  private int lastExitCode = 0;
  private volatile boolean shouldExit = false;
  private final VmOptions options;

  @CompilationFinal
  private SObjectWithoutFields vmMirror;
  @CompilationFinal
  private Actor mainActor;

  public SObjectWithoutFields getVmMirror() {
    return vmMirror;
  }

  public Primitives getPrimitives() {
    return objectSystem.getPrimitives();
  }

  public static void thisMethodNeedsToBeOptimized(final String msg) {
    if (VmSettings.FAIL_ON_MISSING_OPTIMIZATIONS) {
      CompilerAsserts.neverPartOfCompilation(msg);
    }
  }

  public static void callerNeedsToBeOptimized(final String msg) {
    if (VmSettings.FAIL_ON_MISSING_OPTIMIZATIONS) {
      CompilerAsserts.neverPartOfCompilation(msg);
    }
  }

  public static void insertInstrumentationWrapper(final Node node) {
    // TODO: make thread-safe!!!
    // TODO: can I assert that it is locked?? helper on Node??
    if (VmSettings.INSTRUMENTATION) {
      assert node.getSourceSection() != null || (node instanceof WrapperNode) : "Node needs source section, or needs to be wrapper";
      InstrumentationHandler.insertInstrumentationWrapper(node);
    }
  }

  private static void outputToIGV(final Method method) {
    GraphPrintVisitor graphPrinter = new GraphPrintVisitor();

    graphPrinter.beginGraph(method.toString()).visit(method);

    graphPrinter.printToNetwork(true);
    graphPrinter.close();
  }

  public VM(final String[] args, final boolean avoidExitForTesting) throws IOException {
    vm = this;

    this.avoidExitForTesting = avoidExitForTesting;
    options = new VmOptions(args);
    objectSystem = new ObjectSystem(new SourcecodeCompiler(), structuralProbe);
    objectSystem.loadKernelAndPlatform(options.platformFile, options.kernelFile);

    if (options.showUsage) {
      VmOptions.printUsageAndExit();
    }
  }

  public VM(final String[] args) throws IOException {
    this(args, false);
  }

  public static void reportSyntaxElement(final Class<? extends Tags> type,
      final SourceSection source) {
    if (webDebugger != null) {
      webDebugger.reportSyntaxElement(type, source);
    }
  }

  public static void reportParsedRootNode(final Method rootNode) {
    if (webDebugger != null) {
      webDebugger.reportRootNodeAfterParsing(rootNode);
    }

    if (VmSettings.IGV_DUMP_AFTER_PARSING) {
      outputToIGV(rootNode);
    }
  }

  public static void reportLoadedSource(final Source source) {
    if (webDebugger != null) {
      webDebugger.reportLoadedSource(source);
    }
  }

  public static boolean shouldExit() {
    return vm.shouldExit;
  }

  public int lastExitCode() {
    return lastExitCode;
  }

  public static String[] getArguments() {
    return vm.options.args;
  }

  /**
   * Does minimal cleanup and disposes the polyglot engine, before doing a hard
   * exit. This method is expected to be called from main thread.
   */
  public void shutdownAndExit(final int errorCode) {
    if (truffleProfiler != null) {
      truffleProfiler.printHistograms(System.err);
    }

    int code = errorCode;

    Actor.shutDownActorPool();
    ActorExecutionTrace.waitForTrace();
    if (TracingActors.ReplayActor.printMissingMessages() && errorCode == 0) {
      code = 1;
    }
    FutureAssertion.checkFutureAssertions();
    engine.dispose();
    if (VmSettings.MEMORY_TRACING) {
      ActorExecutionTrace.reportPeakMemoryUsage();
    }
    System.exit(code);
  }

  /**
   * Request a shutdown and exit from the VM. This does not happen immediately.
   * Instead, we instruct the main thread to do it, and merely kill the current
   * thread.
   *
   * @param errorCode to be returned as exit code from the program
   */
  public void requestExit(final int errorCode) {
    TruffleCompiler.transferToInterpreter("exit");
    lastExitCode = errorCode;
    shouldExit = true;
    objectSystem.releaseMainThread(errorCode);

    throw new ThreadDeath();
  }

  public static void errorExit(final String message) {
    TruffleCompiler.transferToInterpreter("errorExit");
    errorPrintln("Run-time Error: " + message);
    vm.requestExit(1);
  }

  @TruffleBoundary
  public static void errorPrint(final String msg) {
    // Checkstyle: stop
    System.err.print(msg);
    // Checkstyle: resume
  }

  @TruffleBoundary
  public static void errorPrintln(final String msg) {
    // Checkstyle: stop
    System.err.println(msg);
    // Checkstyle: resume
  }

  @TruffleBoundary
  public static void errorPrintln() {
    // Checkstyle: stop
    System.err.println();
    // Checkstyle: resume
  }

  @TruffleBoundary
  public static void print(final String msg) {
    // Checkstyle: stop
    System.out.print(msg);
    // Checkstyle: resume
  }

  @TruffleBoundary
  public static void println(final String msg) {
    // Checkstyle: stop
    System.out.println(msg);
    // Checkstyle: resume
  }

  @TruffleBoundary
  /**
   * This method is used to print reports about the number of created artifacts.
   * For example actors, messages and promises.
   */
  public static void printConcurrencyEntitiesReport(final String msg) {
    // Checkstyle: stop
    System.out.println(msg);
    // Checkstyle: resume
  }

  public static boolean isAvoidingExit() {
    return vm.avoidExitForTesting;
  }

  public void initalize() {
    assert vmMirror  == null : "VM seems to be initialized already";
    assert mainActor == null : "VM seems to be initialized already";

    mainActor = Actor.createActor();
    vmMirror  = objectSystem.initialize();

    if (VmSettings.ACTOR_TRACING) {
      ActorExecutionTrace.recordMainActor(mainActor, objectSystem);
    }
  }

  public Object execute(final String selector) {
    return objectSystem.execute(selector);
  }

  public void execute() {
    objectSystem.executeApplication(vmMirror, mainActor);
  }

  public static void main(final String[] args) {
    Builder builder = PolyglotEngine.newBuilder();
    builder.config(SomLanguage.MIME_TYPE, SomLanguage.CMD_ARGS,   args);
    builder.config(SomLanguage.MIME_TYPE, SomLanguage.AVOID_EXIT, false);
    VmOptions vmOptions = new VmOptions(args);

    startExecution(builder, vmOptions);
  }

  private static void startExecution(final Builder builder,
      final VmOptions vmOptions) {
    engine = builder.build();

    Map<String, Instrument> instruments = engine.getInstruments();
    Instrument profiler = instruments.get(ProfilerInstrument.ID);
    if (vmOptions.profilingEnabled && profiler == null) {
      VM.errorPrintln("Truffle profiler not available. Might be a class path issue");
    } else {
      profiler.setEnabled(vmOptions.profilingEnabled);
    }

    if (vmOptions.profilingEnabled && profiler != null) {
      truffleProfiler = Profiler.find(engine);
      truffleProfiler.setCollecting(true);
      truffleProfiler.setTiming(true);
    }

    Debugger debugger = null;
    if (VmSettings.TRUFFLE_DEBUGGER_ENABLED) {
      debugger = Debugger.find(engine);
    }

    if (vmOptions.webDebuggerEnabled) {
      assert VmSettings.TRUFFLE_DEBUGGER_ENABLED && debugger != null;
      Instrument webDebuggerInst = instruments.get(WebDebugger.ID);
      webDebuggerInst.setEnabled(true);

      webDebugger = webDebuggerInst.lookup(WebDebugger.class);
      webDebugger.startServer(debugger);
    }

    if (vmOptions.coverageEnabled) {
      Instrument coveralls = instruments.get(Coverage.ID);
      coveralls.setEnabled(true);
      Coverage cov = coveralls.lookup(Coverage.class);
      try {
        cov.setOutputFile(vmOptions.coverageFile);
      } catch (IOException e) {
        VM.errorPrint("Failed to setup coverage tracking: " + e.getMessage());
      }
    }

    if (vmOptions.dynamicMetricsEnabled) {
      assert VmSettings.DYNAMIC_METRICS;
      Instrument dynM = instruments.get(DynamicMetrics.ID);
      dynM.setEnabled(true);
      structuralProbe = dynM.lookup(StructuralProbe.class);
      assert structuralProbe != null : "Initialization of DynamicMetrics tool incomplete";
    }

    engine.eval(SomLanguage.START);
    vm.shutdownAndExit(0);
  }

  public MixinDefinition loadModule(final String filename) throws IOException {
    return objectSystem.loadModule(filename);
  }

  public MixinDefinition loadModule(final Source source) throws IOException {
    return objectSystem.loadModule(source);
  }

  /** This is only meant to be used in unit tests. */
  public static void resetClassReferences(final boolean callFromUnitTest) {
    assert callFromUnitTest;
    SFarReference.setSOMClass(null);
    SPromise.setPairClass(null);
    SPromise.setSOMClass(null);
    SResolver.setSOMClass(null);

    ThreadingModule.ThreadingModule  = null;
    ThreadingModule.ThreadClass      = null;
    ThreadingModule.ThreadClassId    = null;
    ThreadingModule.TaskClass        = null;
    ThreadingModule.TaskClassId      = null;
    ThreadingModule.MutexClass       = null;
    ThreadingModule.MutexClassId     = null;
    ThreadingModule.ConditionClass   = null;
    ThreadingModule.ConditionClassId = null;

    ChannelPrimitives.resetClassReferences();

    KernelObj.indexOutOfBoundsClass = null;
  }
}
