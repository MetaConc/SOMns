package tools.timeTravelling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.debug.DebuggerSession.SteppingLocation;
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
import tools.debugger.FrontendConnector;
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
  private FrontendConnector connector;
  private ArrayList<TimeTravelFrame> frames;

  public TimeTravellingDebugger(final VM vm) {
    this.vm = vm;
    factories = new HashMap<SSymbol, ClassFactory>();
    rootNodes = new HashMap<Long, RootNode>();
    callbackBlocks = new HashMap<Long, SBlock>();
    revivedObjects = new HashMap<Object, SAbstractObject>();
    revivedClasses = new HashMap<SSymbol, SClass>();
  }

  public void setConnector(final FrontendConnector connector) {
   this.connector = connector;
  }

  public void prepareForTimeTravel() {
    // clear the revivedObjects to not work with the state of the previous session
    revivedObjects = new HashMap<Object, SAbstractObject>();
    if (VmSettings.TIME_TRAVELLING) {
      ActorExecutionTrace.forceSwapBuffers();
      VmSettings.TIME_TRAVELLING = false;
      VmSettings.ACTOR_TRACING = false;
      VmSettings.MEMORY_TRACING = false;
      VmSettings.PROMISE_CREATION = false;
      VmSettings.PROMISE_RESOLUTION = false;
      VmSettings.PROMISE_RESOLVED_WITH = false;
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
    timeTravelingActor.send(msg, vm.getActorPool());
  }

  public void sendStoppedMessage(final Suspension suspension) {
    // for each possible stopping point in the turn:
    //  get the stack trace
    //  get the scopes from the first frame in the stack
    //  for each scope get the variable value
    int requestId = 0;
    StackTraceResponse trace = StackTraceResponse.create(0, 1, suspension, requestId); // TODO ensure levels shouldn't be 0
    StackFrame frame = trace.getFirstFrame();
    ArrayList<VariablesResponse> variables = new ArrayList<VariablesResponse>();
    if(frame != null){
      ScopesResponse scope = ScopesResponse.create(frame.id, suspension, requestId);
      for (Scope s : scope.scopes) {
        variables.add(VariablesResponse.create(s.variablesReference, 0, suspension));
      }
    }
    new TimeTravelFrame(variables.toArray(new VariablesResponse[0]));
    if (suspension.getEvent().getLocation() == SteppingLocation.AFTER_CALL) {
      // The message has finished executing, send all stacktraces to the front end
      TimeTravelResponse response = new TimeTravelResponse(requestId, trace, frames.toArray(new TimeTravelFrame[0]));
      vm.getWebDebugger().sendTimeTravelResponse(response);
    } else {
      // The message has not finished executing, perform a step over
      SteppingType.STEP_OVER.process(suspension);
    }
    suspension.resume();
  }
}
