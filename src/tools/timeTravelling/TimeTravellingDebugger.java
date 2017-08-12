package tools.timeTravelling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.nodes.RootNode;

import som.VM;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.interpreter.objectstorage.ClassFactory;
import som.vm.VmSettings;
import som.vmobjects.SAbstractObject;
import som.vmobjects.SBlock;
import som.vmobjects.SClass;
import som.vmobjects.SSymbol;
import tools.concurrency.ActorExecutionTrace;
import tools.debugger.entities.SteppingType;
import tools.debugger.frontend.Suspension;
import tools.debugger.message.ScopesResponse;
import tools.debugger.message.ScopesResponse.Scope;
import tools.debugger.message.StackTraceResponse;
import tools.debugger.message.StackTraceResponse.StackFrame;
import tools.debugger.message.VariablesResponse;
import tools.timeTravelling.TimeTravelResponse.TimeTravelFrame;

// TODO merge file with debugger after thesis is done. This allows me to separate my work from SOM
public class TimeTravellingDebugger {
  private VM vm;
  private Map<SSymbol, ClassFactory> factories;
  private Map<Long, RootNode> rootNodes;
  private Map<Long, SBlock> callbackBlocks;

  /*
   * map keeps track of revived SObject
   * This is necessary as we want to preserve the object state
   * Two objects pointing to the same third object should again point to the same object
   */
  private Map<Object, SAbstractObject> revivedObjects;
  private Map<SSymbol, SClass> revivedClasses;

  /*
   */
  private ArrayList<TimeTravelFrame> frames;

  public TimeTravellingDebugger(final VM vm) {
    this.vm = vm;
    factories = new HashMap<SSymbol, ClassFactory>();
    rootNodes = new HashMap<Long, RootNode>();
    callbackBlocks = new HashMap<Long, SBlock>();
    revivedObjects = new HashMap<Object, SAbstractObject>();
    revivedClasses = new HashMap<SSymbol, SClass>();
  }

  public void prepareForTimeTravel() {
    // clear the revivedObjects to not work with the state of the previous session
    revivedObjects = new HashMap<Object, SAbstractObject>();
    if (VmSettings.timeTravellingRecording) {
      ActorExecutionTrace.forceSwapBuffers();
      VmSettings.timeTravellingRecording = false;
      VmSettings.actorTracing = false;
      VmSettings.memoryTracing = false;
      VmSettings.promiseCreation = false;
      VmSettings.promiseResolution = false;
      VmSettings.promiseResolvedWith = false;
      vm.getWebDebugger().setStrategy(new TimeTravelStrategy(this));
      }
  }

  /*
   *  Runtime information kept to make serialization easier
   */
  public void reportClassFactory(final ClassFactory factory) {
    this.factories.put(factory.getClassName(), factory);
  }
  public ClassFactory getFactory(final SSymbol name) {
    return this.factories.get(name);
  }

  public void reportRootNode(final long messageId, final RootNode rootNode) {
    rootNodes.put(messageId, rootNode);
  }
  public RootNode getRootNode(final long messageId) {
    return rootNodes.get(messageId);
  }

  public void reportSBlock(final Long messageId, final SBlock block) {
    callbackBlocks.put(messageId, block);
  }
  public SBlock getSBlock(final long messageId) {
    return callbackBlocks.get(messageId);
  }

  /*
   *  These methods are used to cache and lookup previously revived objects.
   */
  public synchronized void reportSAbstractObject(final Object dbRef, final SAbstractObject object) {
    revivedObjects.put(dbRef, object);
  }
  public synchronized SAbstractObject getSAbstractObject(final Object dbRef) {
    return revivedObjects.get(dbRef);
  }


  public void reportRevivedSClass(final SSymbol factoryName, final SClass revivedClass) {
    revivedClasses.put(factoryName, revivedClass);
  }
  public SClass getRevivedSClass(final SSymbol factoryName) {
    return revivedClasses.get(factoryName);
  }

  /*
   * actual methods to perform replay, once the system state is restored
   */
  public void replayMessage(final Actor timeTravelingActor, final EventualMessage msg) {
    frames = new ArrayList<TimeTravelFrame>();
    msg.setIsMessageReceiverBreakpoint(true);
    timeTravelingActor.send(msg, vm.getActorPool());
  }

  public void sendStoppedMessage(final Suspension suspension) {
    // for each possible stopping point in the turn:
    //  get the stack trace
    //  get the scopes from the first frame in the stack
    //  for each scope get the variable value
    int requestId = 0;
    StackTraceResponse trace = StackTraceResponse.create(0, 0, suspension, requestId);
    StackFrame frame = trace.getFirstFrame();
    ArrayList<VariablesResponse> variables = new ArrayList<VariablesResponse>();
    ScopesResponse scope = null;
    if (frame != null) {
      scope = ScopesResponse.create(frame.id, suspension, requestId);
      for (Scope s : scope.scopes) {
       VariablesResponse var = VariablesResponse.create(s.variablesReference, 0, suspension);
        variables.add(var);
      }
    }
    frames.add(new TimeTravelFrame(trace, scope, variables.toArray(new VariablesResponse[0])));
    SteppingType.STEP_OVER.process(suspension);
    suspension.resume();
  }

  public void replayFinished() {
    if (VmSettings.timeTravelling && !VmSettings.timeTravellingRecording) {
      // we are time travelling and we have finished recording, a turn is finished => send to front end
      TimeTravelResponse response = new TimeTravelResponse(frames.toArray(new TimeTravelFrame[0]));
      vm.getWebDebugger().sendTimeTravelResponse(response);
    }
  }
}
