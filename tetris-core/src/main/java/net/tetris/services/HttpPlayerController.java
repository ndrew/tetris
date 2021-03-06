package net.tetris.services;

import net.tetris.dom.Figure;
import net.tetris.dom.Joystick;
import net.tetris.dom.TetrisGame;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User: serhiy.zelenin
 * Date: 6/3/12
 * Time: 10:40 PM
 */
public class HttpPlayerController implements PlayerController {
    private static Logger logger = LoggerFactory.getLogger(HttpPlayerController.class);

    private HttpClient client;
    private int timeout;
    private boolean sync = false;
    private String suffix = "/";

    private PlayerControllerListener listener;

    public void requestControl(final Player player, Figure.Type type, int x, int y, final Joystick joystick, List<Plot> plots) throws IOException {
        ContentExchange exchange = new MyContentExchange(joystick, player, listener);

        exchange.setMethod("GET");
        String callbackUrl = player.getCallbackUrl().endsWith("/") ? player.getCallbackUrl() : player.getCallbackUrl() + suffix;
        StringBuilder sb = exportGlassState(plots);

        String url = callbackUrl + "?figure=" + type + "&x=" + x + "&y=" + y + "&glass=" + URLEncoder.encode(sb.toString(), "UTF-8");
        exchange.setURL(url);
        logger.debug("Request control {}, url {}", player, url);
        client.send(exchange);
        if (sync) {
            try {
                exchange.waitForDone();
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting for player response?", e);
            }
        }
    }


    private StringBuilder exportGlassState(List<Plot> plots) {
        char[][] glassState = new char[TetrisGame.GLASS_HEIGHT][TetrisGame.GLASS_WIDTH];
        for (int i = 0; i < TetrisGame.GLASS_HEIGHT; i++) {
            Arrays.fill(glassState[i], ' ');
        }

        for (Plot plot : plots) {
            glassState[plot.getY()][plot.getX()] = '*';
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TetrisGame.GLASS_HEIGHT; i++) {
            sb.append(glassState[i]);
        }
        return sb;
    }

    /**
     * Timeout for player request for the next direction
     *
     * @param timeout
     */
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public void setSync(boolean sync) {
        this.sync = sync;
    }

    public boolean isSync() {
        return sync;
    }

    public void setSuffix(String suffix) {
        this.suffix = suffix;
    }

    public void init() throws Exception {
        client = new HttpClient();
        client.setConnectBlocking(sync);
        client.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        client.setThreadPool(new ExecutorThreadPool(32, 256, timeout, TimeUnit.MILLISECONDS));
        client.setTimeout(timeout);
        client.start();
    }

    public void setListener(PlayerControllerListener listener) {
        this.listener = listener;
    }


    public static class MyContentExchange extends ContentExchange {
        private final Joystick joystick;
        private final Player player;
        private PlayerControllerListener listener;
        private Pattern pattern = Pattern.compile("((left)=(-?\\d*))|((right)=(-?\\d*))|((rotate)=(-?\\d*))|(drop)", Pattern.CASE_INSENSITIVE);

        public MyContentExchange(Joystick joystick, Player player, PlayerControllerListener listener) {
            this.joystick = joystick;
            this.player = player;
            this.listener = listener;
        }

        @Override
        protected void onExpire() {
            logger.warn("Request expired: player: {}, address: {}, request: {}",
                    new Object[]{player.getName(), getAddress(), getRequestURI()});
            if (listener != null) {
                listener.log(player, getRequestURI(), "EXPIRED");
            }
        }

        protected void onResponseComplete() throws IOException {
            String responseContent = this.getResponseContent();
            logger.debug("Received response: {} for request: {}", responseContent, getRequestURI());
            if (listener != null) {
                if (getResponseStatus() != 200) {
                    logger.warn("Received error response: {}, player: {}, address: {}, request: {}",
                            new Object[] {this.getResponseStatus(), player.getName(), getAddress(), getRequestURI()});
                    listener.log(player, getRequestURI(), "ERROR:" + this.getResponseStatus());
                } else {
                    listener.log(player, getRequestURI(), responseContent);
                }

            }
            new PlayerCommand(joystick, responseContent, player).execute();
        }
    }
}
