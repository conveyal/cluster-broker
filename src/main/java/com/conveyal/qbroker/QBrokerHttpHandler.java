package com.conveyal.qbroker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.server.HttpHandler;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;
import org.glassfish.grizzly.http.util.HttpStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
* A Grizzly Async Http Service (uses reponse suspend/resume)
 * https://blogs.oracle.com/oleksiys/entry/grizzly_2_0_httpserver_api1
 *
 * When resuming a response object, "The only reliable way to check the socket status is to try to read or
 * write something." Though you also have:
 *
 * response.getRequest().getRequest().getConnection().isOpen()
 * response.getRequest().getRequest().getConnection().addCloseListener();
 * But none of these work, I've tried all three of them. You can even write to the outputstream after the connection
 * is closed.
 * Solution: networkListener.getTransport().setIOStrategy(SameThreadIOStrategy.getInstance());
 * This makes all three work! isOpen, CloseListener, and IOExceptions from flush();
 *
 * Grizzly has Comet support, but this seems geared toward subscriptions to broadcast events.
 *
*/
class QBrokerHttpHandler extends HttpHandler {

    private ObjectMapper mapper = new ObjectMapper();

    private QBroker qBroker;

    public QBrokerHttpHandler (QBroker qBroker) {
        this.qBroker = qBroker;
    }

    @Override
    public void service(Request request, Response response) throws Exception {

        // request.getRequestURI(); // without protocol or server, only request path
        // request.getPathInfo(); // without handler base path

        response.setContentType("text/plain");

        // may be a partially specified QueuePath without job or task ID
        QueuePath queuePath = new QueuePath(request.getPathInfo());

        // Request body is expected to be JSON. Rather than loading it into a string, we could parse it to a tree
        // or bind to a type immediately. However binding introduces a dependency on the message type classes.
        try {
            if (request.getMethod() == Method.HEAD) {
                /* Let the client know server is alive and URI + request are valid. */
                mapper.readTree(request.getInputStream());
                response.setStatus(HttpStatus.OK_200);
                return;
            } else if (request.getMethod() == Method.GET) {
                /* Return a chunk of tasks for a particular graph. */
                request.getRequest().getConnection().addCloseListener((closeable, iCloseType) -> {
                    qBroker.removeSuspendedResponse(queuePath.graphId, response);
                });
                response.suspend(); // This request should survive after the handler function exits.
                qBroker.registerSuspendedResponse(queuePath.graphId, response);
            } else if (request.getMethod() == Method.POST) {
                /* Enqueue new messages. */
                // Text round trip through JSON is done in the HTTP handler thread, does not block the broker thread.
                JsonNode bodyJson = mapper.readTree(request.getInputStream());
                List<String> taskBodies = new ArrayList<>();
                for (JsonNode node : bodyJson) {
                    taskBodies.add(mapper.writeValueAsString(node));
                }
                qBroker.enqueueTasks(queuePath, taskBodies);
                response.setStatus(HttpStatus.ACCEPTED_202);
            } else if (request.getMethod() == Method.DELETE) {
                /* Acknowledge completion of a task and remove it from queues. */
                if (qBroker.deleteTask(queuePath)) {
                    response.setStatus(HttpStatus.OK_200);
                } else {
                    response.setStatus(HttpStatus.NOT_FOUND_404);
                }
            } else {
                response.setStatus(HttpStatus.BAD_REQUEST_400);
                response.setDetailMessage("Unrecognized HTTP method.");
            }
        } catch (JsonProcessingException jpex) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.setDetailMessage("Could not decode/encode JSON payload. " + jpex.getMessage());
            jpex.printStackTrace();
        } catch (Exception ex) {
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            response.setDetailMessage(ex.toString());
            ex.printStackTrace();
        }
    }

    public void writeJson (Response response, Object object) throws IOException {
        mapper.writeValue(response.getOutputStream(), object);
    }

}
