package tools.timeTravelling;

import static tools.timeTravelling.Database.getDatabaseInstance;

import java.io.IOException;

import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.Transaction;

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
        Transaction transaction = database.startTransaction(session);

        Actor targetActor = EventualMessage.getActorCurrentMessageIsExecutionOn();

        if (!targetActor.inDatabase) {
          database.createActor(transaction, targetActor);
        }

        database.createCheckpoint(transaction, messageId, msg, targetActor.getId(), target);
        database.commitTransaction(transaction);
        database.endSession(session);

      } else if (t instanceof SClass) {
        // method is either a constructor or static method, no target object, only store arguments
        SClass target = (SClass) t;

        Database database = getDatabaseInstance();
        Session session = database.startSession();
        Transaction transaction = database.startTransaction(session);

        Actor targetActor = EventualMessage.getActorCurrentMessageIsExecutionOn();

        if (!targetActor.inDatabase) { // can one create an actor of a class instance instead of a class
          database.createActor(transaction, targetActor);
        }

        database.createConstructor(transaction, messageId, msg, targetActor.getId(), target);

        database.commitTransaction(transaction);
        database.endSession(session);

      } else {
        VM.println("ignored: " + t.getClass() + " " + t.toString());
      }
    } finally {

    }
  }
}
