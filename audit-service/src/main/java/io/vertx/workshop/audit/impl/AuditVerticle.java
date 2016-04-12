package io.vertx.workshop.audit.impl;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.discovery.types.MessageSource;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.workshop.common.MicroServiceVerticle;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A verticle storing operations in a database (hsql) and providing access to the operations.
 */
public class AuditVerticle extends MicroServiceVerticle {

  private JDBCClient jdbc;

  /**
   * Starts the verticle asynchronously. The the initialization is completed, it calls `complete()` on the given
   * {@link Future} object. If something wrong happens, `fail` is called.
   *
   * @param future the future to indicate the completion
   */
  @Override
  public void start(Future<Void> future) {
    super.start();

    // creates the jdbc client.
    jdbc = JDBCClient.createNonShared(vertx, config());

    // Create 2 futures:
    // 1. one for the HTTP server
    // 2. one for the database initialization (table creation)
    Future<HttpServer> httpServerReady = Future.future();
    Future<Void> databaseReady = Future.future();

    // Create the table if needed
    createTableIfNeeded(databaseReady, config().getBoolean("drop", false));


    // Use a Vert.x Web router for this REST API.
    Router router = Router.router(vertx);
    router.get("/").handler(this::retrieveOperations);

    vertx.createHttpServer()
        .requestHandler(router::accept)
        .listen(8080, httpServerReady.completer());


    /**
     * Retrieve the message soruce service on which the action are sent.
     */
    MessageSource.<JsonObject>get(vertx, discovery, new JsonObject().put("name", "portfolio-events"), consumer -> {
      if (consumer.failed()) {
        System.err.println("No portfolio-events service, did you start the portfolio-service ?");
      } else {
        consumer.result().handler(
            message -> {
              storeInDatabase(message.body());
            }
        );
      }
    });


    CompositeFuture.all(httpServerReady, databaseReady)
        .setHandler(ar -> {
          if (ar.succeeded()) {
            future.complete();
          } else {
            future.fail(ar.cause());
          }
        });
  }

  @Override
  public void stop(Future<Void> future) throws Exception {
    jdbc.close();
    super.stop(future);
  }

  private void storeInDatabase(JsonObject operation) {
    String sql = "INSERT INTO AUDIT (operation) VALUES ?";

    Future<SQLConnection> connectionFuture = Future.future();
    Future<Void> completionFuture = Future.future();
    jdbc.getConnection(connectionFuture.completer());

    connectionFuture
        .compose(connection -> {
          connection.updateWithParams(sql,
              new JsonArray().add(operation.encode()),
              ar -> {
                if (ar.failed()) {
                  completionFuture.fail(ar.cause());
                } else {
                  connection.close(completionFuture.completer());
                }
              });
        }, completionFuture);


    completionFuture.setHandler(ar -> {
      if (ar.failed()) {
        System.err.println("Failed to insert operation in database: " + ar.cause());
      } else {
        System.out.println("Operation inserted");
      }
    });
  }

  private void createTableIfNeeded(Future<Void> future, Boolean drop) {
    Future<SQLConnection> connectionFuture = Future.future();
    Future<SQLConnection> droppedFuture = Future.future();
    Future<SQLConnection> closeFuture = Future.future();

    Handler<SQLConnection> dropTable = connection -> {
      if (!drop) {
        droppedFuture.complete(connection);
        return;
      }
      connection.execute("DROP TABLE IF EXISTS AUDIT", v -> {
        if (v.failed()) {
          droppedFuture.fail(v.cause());
        } else {
          droppedFuture.complete(connection);
        }
      });
    };

    Handler<SQLConnection> createTable = connection -> {
      connection.execute(
          "CREATE TABLE IF NOT EXISTS AUDIT (id INTEGER IDENTITY, operation varchar(250))", v -> {
            if (v.failed()) {
              closeFuture.fail(v.cause());
            } else {
              closeFuture.complete(connection);
            }
          });
    };

    Handler<SQLConnection> closeConnection = connection -> {
      connection.close(future.completer());
    };

    jdbc.getConnection(connectionFuture.completer());

    connectionFuture.compose(dropTable, droppedFuture);
    droppedFuture.compose(createTable, closeFuture);
    closeFuture.compose(closeConnection, future);
  }

  private void retrieveOperations(RoutingContext context) {
    jdbc.getConnection(ar -> {
      SQLConnection connection = ar.result();
      connection.query("SELECT * FROM AUDIT ORDER BY ID DESC LIMIT 10", result -> {
        ResultSet set = result.result();
        List<JsonObject> operations = set.getRows().stream()
            .map(json -> new JsonObject(json.getString("OPERATION")))
            .collect(Collectors.toList());
        context.response().setStatusCode(200).end(Json.encodePrettily(operations));
        connection.close();
      });
    });
  }
}
