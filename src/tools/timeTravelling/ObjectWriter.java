package tools.timeTravelling;

import static tools.timeTravelling.Database.getDatabaseInstance;

import java.io.IOException;

import org.neo4j.driver.v1.Session;

import som.VM;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.vmobjects.SClass;
import som.vmobjects.SObject;
import som.vmobjects.SObject.SMutableObject;

public  class ObjectWriter {

  public static void writeMessage(final Long messageId, final EventualMessage msg, final Object t) throws IOException {
    try {
      if (t instanceof SMutableObject) {
        // TODO ensure the platform is the only possible immutable top level object
        SObject target = (SObject) t;

        Database database = getDatabaseInstance();
        Session session = database.startSession();

        Actor targetActor = EventualMessage.getActorCurrentMessageIsExecutionOn();

        if (!targetActor.inDatabase) { // not a race condition as actors only process one message at a time
          database.createActor(session, targetActor);
        }

        database.createCheckpoint(session, messageId, msg, targetActor.getId(), target);
        database.endSession(session);

      } else if (t instanceof SClass) {
        // method is either a constructor or static method, no target object, only store arguments
        SClass target = (SClass) t;

        Database database = getDatabaseInstance();
        Session session = database.startSession();

        Actor targetActor = EventualMessage.getActorCurrentMessageIsExecutionOn();

        if (!targetActor.inDatabase) { // can one create an actor of a SObject instance instead of of a class
          database.createActor(session, targetActor);
        }

        database.createConstructor(session, messageId, msg, targetActor.getId(), target);

        database.endSession(session);

      } else {
        VM.println("ignored: " + t.getClass() + " " + t.toString());
      }
    } finally {

    }
  }
}
