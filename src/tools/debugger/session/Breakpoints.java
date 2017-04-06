package tools.debugger.session;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.debug.Breakpoint;
import com.oracle.truffle.api.debug.Breakpoint.SimpleCondition;
import com.oracle.truffle.api.debug.Debugger;
import com.oracle.truffle.api.debug.DebuggerSession;
import com.oracle.truffle.api.debug.DebuggerSession.SteppingLocation;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.instrumentation.StandardTags.RootTag;
import com.oracle.truffle.api.source.SourceSection;

import som.VM;
import som.interpreter.actors.EventualMessage;
import som.interpreter.actors.ReceivedRootNode;
import som.vm.VmSettings;
import tools.SourceCoordinate;
import tools.SourceCoordinate.FullSourceCoordinate;
import tools.concurrency.Tags.ExpressionBreakpoint;
import tools.debugger.WebDebugger;
import tools.debugger.nodes.AbstractBreakpointNode;
import tools.debugger.nodes.BreakpointNodeGen;
import tools.debugger.nodes.DisabledBreakpointNode;
import tools.debugger.stepping.StepActorOperation;
import tools.debugger.stepping.Stepping;
import tools.debugger.stepping.Stepping.SteppingType;


public class Breakpoints {

  private final DebuggerSession debuggerSession;

  /**
   * Breakpoints directly managed by Truffle.
   */
  private final Map<BreakpointInfo, Breakpoint> truffleBreakpoints;

  /**
   * MessageReceiverBreakpoints, manually managed by us (instead of Truffle).
   */
  private final Map<FullSourceCoordinate, BreakpointEnabling<MessageReceiverBreakpoint>> receiverBreakpoints;


  /**
   * PromiseResolverBreakpoint, manually managed by us (instead of Truffle).
   */
  private final Map<FullSourceCoordinate, BreakpointEnabling<PromiseResolverBreakpoint>> promiseResolverBreakpoints;

  /**
   * PromiseResolutionBreakpoint, manually managed by us (instead of Truffle).
   */
  private final Map<FullSourceCoordinate, BreakpointEnabling<PromiseResolutionBreakpoint>> promiseResolutionBreakpoints;

  /** Manually managed by us, instead of Truffle. */
  private final Map<FullSourceCoordinate, BreakpointEnabling<ChannelOppositeBreakpoint>> channelOppositeBreakpoint;

  private Stepping stepping;

  public Breakpoints(final Debugger debugger, final WebDebugger webDebugger) {
    this.truffleBreakpoints           = new HashMap<>();
    this.receiverBreakpoints          = new HashMap<>();
    this.promiseResolverBreakpoints   = new HashMap<>();
    this.promiseResolutionBreakpoints = new HashMap<>();
    this.channelOppositeBreakpoint    = new HashMap<>();
    this.debuggerSession = debugger.startSession(webDebugger);
    this.stepping = null;
  }

  public void doSuspend(final MaterializedFrame frame, final SteppingLocation steppingLocation) {
    debuggerSession.doSuspend(frame, steppingLocation);
  }

  public void prepareSteppingUntilNextRootNode() {
    debuggerSession.prepareSteppingUntilNextRootNode();
  }

  public void prepareSteppingAfterNextRootNode() {
    debuggerSession.prepareSteppingAfterNextRootNode();
  }

  public synchronized void addOrUpdate(final LineBreakpoint bId) {
    Breakpoint bp = truffleBreakpoints.get(bId);
    if (bp == null) {
      WebDebugger.log("LineBreakpoint: " + bId);
      bp = Breakpoint.newBuilder(bId.getURI()).
          lineIs(bId.getLine()).
          build();
      debuggerSession.install(bp);
      truffleBreakpoints.put(bId, bp);
    }
    bp.setEnabled(bId.isEnabled());
  }

  public synchronized void addOrUpdate(final MessageSenderBreakpoint bId) {
    saveTruffleBasedBreakpoints(bId, ExpressionBreakpoint.class, null);
  }

  public synchronized void addOrUpdate(final AsyncMessageBeforeExecutionBreakpoint bId) {
    Breakpoint bp = saveTruffleBasedBreakpoints(bId, RootTag.class, null);
    bp.setCondition(BreakWhenActivatedByAsyncMessage.INSTANCE);
  }

  public synchronized void addOrUpdate(final AsyncMessageAfterExecutionBreakpoint bId) {
    Breakpoint bp = saveTruffleBasedBreakpoints(bId, RootTag.class, SteppingLocation.AFTER_STATEMENT);
    bp.setCondition(BreakWhenActivatedByAsyncMessage.INSTANCE);
  }

  public synchronized void addOrUpdate(final MessageReceiverBreakpoint bId) {
    saveBreakpoint(bId, receiverBreakpoints);
  }

  public synchronized void addOrUpdate(final PromiseResolverBreakpoint bId) {
    saveBreakpoint(bId, promiseResolverBreakpoints);
  }

  public synchronized void addOrUpdate(final PromiseResolutionBreakpoint bId) {
    saveBreakpoint(bId, promiseResolutionBreakpoints);
  }

  public synchronized void addOrUpdate(final ChannelOppositeBreakpoint bId) {
    saveBreakpoint(bId, channelOppositeBreakpoint);
  }

  private Breakpoint saveTruffleBasedBreakpoints(final SectionBreakpoint bId, final Class<?> tag, final SteppingLocation sl) {
    Breakpoint bp = truffleBreakpoints.get(bId);
    if (bp == null) {
      bp = Breakpoint.newBuilder(bId.getCoordinate().uri).
          lineIs(bId.getCoordinate().startLine).
          columnIs(bId.getCoordinate().startColumn).
          sectionLength(bId.getCoordinate().charLength).
          tag(tag).
          steppingLocation(sl).
          build();
      debuggerSession.install(bp);
      truffleBreakpoints.put(bId, bp);
    }
    bp.setEnabled(bId.isEnabled());
    return bp;
  }

  private <T extends SectionBreakpoint> void saveBreakpoint(final T bId,
      final Map<FullSourceCoordinate, BreakpointEnabling<T>> breakpoints) {
    FullSourceCoordinate coord = bId.getCoordinate();
    BreakpointEnabling<T> existingBP = breakpoints.get(coord);
    if (existingBP == null) {
      existingBP = new BreakpointEnabling<T>(bId);
      breakpoints.put(coord, existingBP);
    } else {
      existingBP.setEnabled(bId.isEnabled());
    }
  }

  private static final class BreakWhenActivatedByAsyncMessage implements SimpleCondition {
    static BreakWhenActivatedByAsyncMessage INSTANCE = new BreakWhenActivatedByAsyncMessage();

    private BreakWhenActivatedByAsyncMessage() { }

    @Override
    public boolean evaluate() {
      RootCallTarget ct = (RootCallTarget) Truffle.getRuntime().getCallerFrame().getCallTarget();
      return (ct.getRootNode() instanceof ReceivedRootNode);
    }
  }

 public synchronized BreakpointEnabling<MessageReceiverBreakpoint> getReceiverBreakpoint(
      final FullSourceCoordinate section) {
   BreakpointEnabling<MessageReceiverBreakpoint> bkp = receiverBreakpoints.computeIfAbsent(section,
        ss -> new BreakpointEnabling<>(new MessageReceiverBreakpoint(false, section)));

   if (bkp.isDisabled() && stepping != null) {
     bkp.setEnabled(stepping.getSteppingTypeOperation(section) == SteppingType.STEP_INTO);
   }

   return bkp;
  }

  public synchronized BreakpointEnabling<PromiseResolverBreakpoint> getPromiseResolverBreakpoint(
      final FullSourceCoordinate section) {
    return promiseResolverBreakpoints.computeIfAbsent(section,
        ss -> new BreakpointEnabling<>(new PromiseResolverBreakpoint(false, section)));
  }

  public synchronized BreakpointEnabling<PromiseResolutionBreakpoint> getPromiseResolutionBreakpoint(
      final FullSourceCoordinate section) {
    return promiseResolutionBreakpoints.computeIfAbsent(section,
        ss -> new BreakpointEnabling<>(new PromiseResolutionBreakpoint(false, section)));
  }

  public synchronized BreakpointEnabling<ChannelOppositeBreakpoint> getOppositeBreakpoint(
      final FullSourceCoordinate section) {
    return channelOppositeBreakpoint.computeIfAbsent(section,
        ss -> new BreakpointEnabling<>(new ChannelOppositeBreakpoint(false, section)));
  }

  public static AbstractBreakpointNode createPromiseResolver(final SourceSection source) {
    if (VmSettings.TRUFFLE_DEBUGGER_ENABLED) {
      FullSourceCoordinate sourceCoord = SourceCoordinate.create(source);
      Breakpoints breakpointCatalog = VM.getWebDebugger().getBreakpoints();
      return BreakpointNodeGen.create(breakpointCatalog.getPromiseResolverBreakpoint(sourceCoord));
    } else {
      return new DisabledBreakpointNode();
    }
  }

  public static AbstractBreakpointNode createPromiseResolution(final SourceSection source) {
    if (VmSettings.TRUFFLE_DEBUGGER_ENABLED) {
      FullSourceCoordinate sourceCoord = SourceCoordinate.create(source);
      Breakpoints breakpointCatalog = VM.getWebDebugger().getBreakpoints();
      return BreakpointNodeGen.create(breakpointCatalog.getPromiseResolutionBreakpoint(sourceCoord));
    } else {
      return new DisabledBreakpointNode();
    }
  }

  public static AbstractBreakpointNode createReceiver(final SourceSection source) {
    if (VmSettings.TRUFFLE_DEBUGGER_ENABLED) {
      FullSourceCoordinate sourceCoord = SourceCoordinate.create(source);
      Breakpoints breakpointCatalog = VM.getWebDebugger().getBreakpoints();
      return BreakpointNodeGen.create(breakpointCatalog.getReceiverBreakpoint(sourceCoord));
    } else {
      return new DisabledBreakpointNode();
    }
  }

  public static AbstractBreakpointNode createOpposite(final SourceSection source) {
    if (VmSettings.TRUFFLE_DEBUGGER_ENABLED) {
      FullSourceCoordinate sourceCoord = SourceCoordinate.create(source);
      Breakpoints breakpointCatalog = VM.getWebDebugger().getBreakpoints();
      return BreakpointNodeGen.create(breakpointCatalog.getOppositeBreakpoint(sourceCoord));
    } else {
      return new DisabledBreakpointNode();
    }
  }

  public void setActorStepping(final StepActorOperation step) {
    this.stepping = new Stepping(step);
  }

  public Stepping getStepping() {
    return this.stepping;
  }

/**
 * Return the stepping operation corresponding for a sourceSection.
 */
  public static SteppingType checkSteppingOperation(final SourceSection source) {
    if (VmSettings.TRUFFLE_DEBUGGER_ENABLED) {
      FullSourceCoordinate sourceCoord = SourceCoordinate.create(source);
      Breakpoints breakpointCatalog = VM.getWebDebugger().getBreakpoints();
      if (breakpointCatalog.getStepping() != null) {
        return breakpointCatalog.getStepping().getSteppingTypeOperation(sourceCoord);
      }
    }

    return null;
  }

  public void prepareStepIntoMessage() {
    disableBreakpoints();
    prepareSteppingUntilNextRootNode();
  }

  public void prepareStepReturnFromMessage(final EventualMessage msg) {
    disableBreakpoints();
    if (msg.getResolver() != null) {
      msg.getResolver().getPromise().setTriggerStepReturnOnCallbacks(true);
    }
  }

  /**
   * Disable existing breakpoints in the debugger session.
   */
    public void disableBreakpoints() {
      List<Breakpoint> list = debuggerSession.getBreakpoints();
      for (Breakpoint breakpoint : list) {
        if (breakpoint.isEnabled()) {
          breakpoint.setEnabled(false);
        }
      }
      this.stepping = null;
    }
}
