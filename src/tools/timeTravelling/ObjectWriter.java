package tools.timeTravelling;

import static tools.timeTravelling.Database.getDatabaseInstance;

import org.neo4j.driver.v1.Session;

import som.VM;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.vmobjects.SClass;
import som.vmobjects.SObject;

public  class ObjectWriter {

  // messageId  = id of message currently being executed
  // msg        = message currently being executed
  // t          = targe tof message
  // messageCount = number of message in actor message queue, includes current message.
  public static void writeMessage(final Long messageId, final EventualMessage msg, final Object t, final VM vm, final int messageCount) {
    try {
      if (t instanceof SObject) {
        SObject target = (SObject) t;
        if (vm.isPlatformObject(target)) {
          System.out.println("is platform object"); // what information do we need from the platform object / actor?
        } else {
          Database database = getDatabaseInstance();
          Session session = database.startSession();
          Actor targetActor = EventualMessage.getActorCurrentMessageIsExecutionOn();
          database.storeCheckpoint(session, messageId, msg, targetActor, target, messageCount);
          database.endSession(session);
        }
      } else if (t instanceof SClass) {
        // method is either a constructor or static method, no target object, only store arguments
        SClass target = (SClass) t;
        Database database = getDatabaseInstance();
        Session session = database.startSession();
        Actor targetActor = EventualMessage.getActorCurrentMessageIsExecutionOn();
        database.storeFactoryMethod(session, messageId, msg, targetActor, target, messageCount);
        database.endSession(session);
      } else {
        VM.println("ignored: " + t.getClass() + " " + t.toString());
      }
    } catch (Exception e) {
      VM.errorPrintln(e.getMessage());
    }
  }
}
