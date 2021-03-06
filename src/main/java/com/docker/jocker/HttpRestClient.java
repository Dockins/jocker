package com.docker.jocker;

import com.docker.jocker.io.ChunkedInputStream;
import com.docker.jocker.io.ContentLengthInputStream;
import com.docker.jocker.model.ErrorDetail;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.apache.commons.io.IOUtils;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.Socket;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

public class HttpRestClient {

    protected final URI uri;
    protected final String host;
    protected final Gson gson;

    public HttpRestClient(URI uri, SSLContext ssl) {
        this.uri = uri;
        this.gson = new GsonBuilder().create();
        if ("unix".equals(uri.getScheme())) {
            this.host = "docker";
        } else {
            this.host = uri.getHost();
            // FIXME add support for SSL on http
        }
    }

    protected Socket getSocket() throws IOException {
        if ("unix".equals(uri.getScheme())) {
            UnixSocketAddress address = new UnixSocketAddress(new File(uri.getPath()));
            return UnixSocketChannel.open(address).socket();
        } else {
            return new Socket(host, uri.getPort());
        }
    }

    public Response doGET(String path) throws IOException {
        final Socket socket = getSocket();
        final OutputStream out = socket.getOutputStream();

        final PrintWriter w = new PrintWriter(out);
        w.println("GET " + path + " HTTP/1.1");
        w.println("Host: "+host);
        w.println();
        w.flush();

        return getResponse(socket);
    }

    public Response doPOST(String path) throws IOException {
        return doPOST(path, "");
    }

    public Response doPOST(String path, String payload) throws IOException {
        return doPOST(path, payload, Collections.EMPTY_MAP);
    }

    public Response doPOST(String path, InputStream payload, Map<String, String> headers) throws IOException {
        final Socket socket = getSocket();
        return doPOST(socket, path, payload, headers);
    }

    protected Response doPOST(Socket socket, String path, InputStream payload, Map<String, String> headers) throws IOException {
        final OutputStream out = socket.getOutputStream();
        if (!headers.containsKey("Content-Type")) {
            headers.put("Content-Type", "application/json; charset=utf-8");
        }

        final PrintWriter w = new PrintWriter(out);
        w.println("POST " + path + " HTTP/1.1");
        w.println("Host: "+host);
        w.println("Transfer-Encoding: chunked");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            w.println(header.getKey() +": "+header.getValue());
        }
        w.println();
        w.flush();

        byte[] buffer = new byte[CHUNK_SIZE];
        int read;
        while ((read = payload.read(buffer, 0, CHUNK_SIZE)) > 0) {
            out.write(Integer.toHexString(read).getBytes(US_ASCII));
            out.write(CRLF);
            out.write(buffer, 0, read);
            out.write(CRLF);
            System.out.println("wrote "+ read);
        }
        out.write(CHUNK_END);
        out.flush();

        return getResponse(socket);
    }

    private final byte[] CRLF = "\r\n".getBytes(US_ASCII);
    private final byte[] CHUNK_END = "0\r\n\r\n".getBytes(US_ASCII);
    private final int CHUNK_SIZE = 4 * 1024;



    public Response doPOST(String path, String payload, Map<String, String> headers) throws IOException {
        return doPOST(path, payload.getBytes(UTF_8), headers);
    }

    public Response doPOST(String path, byte[] payload, Map<String, String> headers) throws IOException {
        final Socket socket = getSocket();
        return doPOST(socket, path, payload, headers);
    }

    protected Response doPOST(Socket socket, String path, byte[] payload, Map<String, String> headers) throws IOException {
        final OutputStream out = socket.getOutputStream();
        final PrintWriter w = new PrintWriter(out);
        w.println("POST " + path + " HTTP/1.1");
        w.println("Host: "+host);
        w.println("Content-Type: application/json; charset=utf-8");
        w.println("Content-Length: "+payload.length);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            w.println(header.getKey() +": "+header.getValue());
        }
        w.println();
        w.flush();
        out.write(payload);

        return getResponse(socket);
    }

    public Response doHEAD(String path) throws IOException {
        final Socket socket = getSocket();
        final OutputStream out = socket.getOutputStream();
        final PrintWriter w = new PrintWriter(out);
        w.println("HEAD " + path + " HTTP/1.1");
        w.println("Host: "+host);
        w.println();
        w.flush();
        return getResponse(socket);
    }


    public Response doPUT(String path, byte[] bytes) throws IOException {
        final Socket socket = getSocket();
        final OutputStream out = socket.getOutputStream();

        final PrintWriter w = new PrintWriter(out);
        w.println("PUT " + path + " HTTP/1.1");
        w.println("Host: "+host);
        w.println("Content-Type: application/gzip");
        w.println("Content-Length: "+bytes.length);
        w.println();
        w.flush();
        out.write(bytes);

        return getResponse(socket);
    }

    public Response doDELETE(String path) throws IOException {
        final Socket socket = getSocket();
        final OutputStream out = socket.getOutputStream();

        final PrintWriter w = new PrintWriter(out);
        w.println("DELETE " + path + " HTTP/1.1");
        w.println("Host: "+host);
        w.println();
        w.flush();

        return getResponse(socket);
    }

    private Response getResponse(Socket socket) throws IOException {
        final InputStream in = socket.getInputStream();
        int status = readHttpStatus(in);
        Map<String, String> headers = readHttpResponseHeaders(in);

        Response<?> response;
        if (headers.containsKey("Content-Length")) {
            final int length = Integer.parseInt(headers.get("Content-Length"));
            response = new Response(headers, new ContentLengthInputStream(in, length), socket);
        } else if (headers.containsKey("Transfer-Encoding") && "chunked".equals(headers.get("Transfer-Encoding"))) {
            response = new Response(headers, new ChunkedInputStream(in), socket);
        } else {
            response = new Response(headers, in, socket);
        }

        if (status / 100 > 2) {
            String message = String.valueOf(status);
            final String type = headers.get("Content-Type");
            if (type != null && type.startsWith("application/json")) {
                message = gson.fromJson(response.readBody(), ErrorDetail.class).getMessage();
            }
            if (status == 404) {
                throw new NotFoundException(message);
            }
            if (status == 409) {
                throw new ConflictException(message);
            }
            throw new IOException(message);
        }
        return response;
    }

    private Map<String, String> readHttpResponseHeaders(InputStream in) throws IOException {
        String line;
        int responseCode;

        Map<String, String> headers = new HashMap<>();
        int length = -1;
        boolean chunked = false;
        while ((line = readLine(in)) != null) {
            if (line.length() == 0) break; // end of header
            final int x = line.indexOf(':');
            String header = line.substring(0, x);
            final String value = line.substring(x + 2);
            headers.put(header, value);
        }
        return headers;
    }

    private int readHttpStatus(InputStream in) throws IOException {
        String line = readLine(in);
        int i = line.indexOf(' ');
        int j = line.indexOf(' ',  i+1);
        return Integer.parseInt(line.substring(i+1,j));
    }

    private String readLine(final InputStream in) throws IOException {
        StringBuilder s = new StringBuilder();
        char c;
        while((c = (char) in.read()) != '\r') {
            s.append(c);
        }
        in.read(); // \n
        return s.toString();
    }

    public Reader readPayload(final InputStream in, int length) throws IOException {
        return new InputStreamReader(new ContentLengthInputStream(in, length), UTF_8);
    }

    public Reader readChunkedPayload(final InputStream in) throws IOException {
        return new InputStreamReader(new ChunkedInputStream(in), UTF_8);
    }

    public static class Response<I extends InputStream> implements AutoCloseable {
        private final I body;
        private final Map<String,String> headers;
        private final Closeable close;

        public Response(Map<String, String> headers, I body, Closeable close) {
            this.body = body;
            this.headers = headers;
            this.close = close;
        }

        @Override
        public void close() throws IOException {
            close.close();
        }

        public String readBody() throws IOException {
            return IOUtils.toString(body, UTF_8);
        }

        public I getBody() {
            return body;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }
    }
}
