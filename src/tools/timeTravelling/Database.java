package tools.timeTravelling;

import static org.neo4j.driver.v1.Values.parameters;

import java.io.IOException;

import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;

import som.vmobjects.SObject;

public final class Database {
  private static Database singleton;
  private Driver driver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "timetraveling"));


  private Database() {
  }

  public static Database getDatabaseInstance() {
    if (singleton == null) {
      singleton = new Database();
    }
    return singleton;
  }

  private StatementResult query(final String query, final Object... params) {
    try (Session session = driver.session()) {
      try (Transaction tx = session.beginTransaction()) {
        StatementResult result = tx.run(query, parameters(params));
        tx.success();
        return result;
      }
    } catch (Exception e) {
      System.out.println(e.getMessage());
      return null;
    }
  }

  private void writeCheckpoint(final int id, final String className, final String string) {
    query("CREATE (a:checkpoint {id: {id}, class: {class}, object: {object}})", "id", id, "class", className, "object", string);
  }

  private Record readCheckpoint(final int id) {
    StatementResult result = query("MATCH (a:checkpoint) WHERE a.id = {id} RETURN a.object AS object, a.class AS class", "id", 1);
    return result.single();
  }

  public void createCheckpoint(final SObject object) {
    try {
      String string = ObjectWriter.serializeObject(object);
      String className = ObjectWriter.getClassName(object);
      writeCheckpoint(1, className, string);
    } catch (Exception e) {
      System.out.println("creating checkpoint failed: " + e.getMessage());
    }
  }

  public Object getCheckpoint() throws ClassNotFoundException, IOException {
    Record result = readCheckpoint(1);
    String object = result.get("object").asString();
    String className = result.get("class").asString();
    return ObjectWriter.decodeObject(object);
  }
}
