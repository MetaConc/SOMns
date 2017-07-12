package tools.timeTravelling;

import static tools.timeTravelling.Database.getDatabaseInstance;

import org.neo4j.driver.v1.Session;

import som.VM;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.interpreter.actors.EventualMessage.DirectMessage;
import som.interpreter.actors.EventualMessage.PromiseCallbackMessage;
import som.interpreter.actors.EventualMessage.PromiseSendMessage;

public  class ObjectWriter {

  // messageId  = id of message currently being executed
  // msg        = message currently being executed
  // t          = targe tof message
  // messageCount = number of message in actor message queue, includes current message.
  public static void writeMessage(final Long messageId, final EventualMessage msg, final VM vm) {
    try {
      if (msg instanceof PromiseSendMessage) {
        Database database = getDatabaseInstance();
        Session session = database.startSession();
        Actor targetActor = EventualMessage.getActorCurrentMessageIsExecutionOn();
        database.storeSendMessageTurn(session, messageId, (PromiseSendMessage) msg, targetActor);
        database.endSession(session);
      } else if (msg instanceof PromiseCallbackMessage) {
        Database database = getDatabaseInstance();
        Session session = database.startSession();
        Actor targetActor = EventualMessage.getActorCurrentMessageIsExecutionOn();
        database.storeCallbackMessageTurn(session, messageId, (PromiseCallbackMessage) msg, targetActor);
        database.endSession(session);
      } else if(msg instanceof DirectMessage) {
        if(msg.getMessageId() == 0) {
            // This is the start message
          return;
        }
        Database database = getDatabaseInstance();
        Session session = database.startSession();
        Actor targetActor = EventualMessage.getActorCurrentMessageIsExecutionOn();
        database.storeDirectMessageTurn(session, messageId, (DirectMessage) msg, targetActor);
        database.endSession(session);
      } else {
        VM.errorPrintln("unexpected type of message: " + msg.getClass());
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
