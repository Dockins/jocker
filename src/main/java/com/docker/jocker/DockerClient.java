package com.docker.jocker;


import com.docker.jocker.io.ChunkedInputStream;
import com.docker.jocker.io.DockerMultiplexedInputStream;
import com.docker.jocker.model.*;
import com.google.gson.stream.JsonReader;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.Socket;
import java.net.URI;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Implement <a href="https://docs.docker.com/engine/api/v1.40">Docker API</a> using a plain old java
 * {@link Socket}.
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class DockerClient extends HttpRestClient {

    private String version;
    
    public DockerClient(String dockerHost) throws IOException {
        this(dockerHost, null);
    }

    public DockerClient(String dockerHost, SSLContext ssl) throws IOException {
        super(URI.create(dockerHost), ssl);
        version = version().getApiVersion();
    }

    public SystemVersion version() throws IOException {
        try (HttpRestClient.Response r = doGET("/version")) {
            return gson.fromJson(r.readBody(), SystemVersion.class);
        }
    }

    public SystemInfo info() throws IOException {
        try (HttpRestClient.Response r = doGET("/v"+version+"/info")) {
            return gson.fromJson(r.readBody(), SystemInfo.class);
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerList
     */
    public ContainerSummary containerList(boolean all, int limit, boolean size, ContainersFilters filters) throws IOException {
        return containerList(all, limit, size, gson.toJson(filters));
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerList
     */
    public ContainerSummary containerList(boolean all, int limit, boolean size, String filters) throws IOException {
        Request req = Request("/v", version, "/containers/json")
            .query("all", all)
            .query("size", size)
            .query("filters", filters)
            .query("limit", limit);

        try (HttpRestClient.Response r = doGET(req.toString())) {
            return gson.fromJson(r.readBody(), ContainerSummary.class);
        }
    }

    /**
     * copy a single file to a container
     * see https://docs.docker.com/engine/api/v1.40/#operation/PutContainerArchive
     */
    public void putContainerArchive(String container, String path, boolean noOverwriteDirNonDir, byte[] tar) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/archive")
            .query("path", path)
            .query("noOverwriteDirNonDir", noOverwriteDirNonDir);

        try (HttpRestClient.Response r = doPUT(req.toString(), tar)) {}
    }

    /** Helper method to put a single file inside container */
    public void putContainerFile(String container, String path, boolean noOverwriteDirNonDir, File file) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gz);
             InputStream in = new FileInputStream(file)) {
            final ArchiveEntry entry = tar.createArchiveEntry(file, file.getName());
            tar.putArchiveEntry(entry);
            IOUtils.copy(in, tar);
            tar.closeArchiveEntry();
        }
        putContainerArchive(container, path, noOverwriteDirNonDir, bos.toByteArray());
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerCreate
     */
    public ContainerCreateResponse containerCreate(ContainerConfig containerSpec, String name) throws IOException {
        Request req = Request("/v", version, "/containers/create")
                .query("name", name);

        String spec = gson.toJson(containerSpec);
        try (HttpRestClient.Response r = doPOST(req.toString(), spec)) {
            return gson.fromJson(r.readBody(), ContainerCreateResponse.class);
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerStart
     */
    public void containerStart(String container) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/start");
        try (HttpRestClient.Response r = doPOST(req.toString())) {}
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerStop
     */
    public void containerStop(String container, int timeout) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/stop")
                .query("t", timeout);
        try (HttpRestClient.Response r = doPOST(req.toString())) {}
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerRestart
     */
    public void containerRestart(String container, int timeout) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/restart")
                .query("t", timeout);
        try (HttpRestClient.Response r = doPOST(req.toString())) {}
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerResize
     */
    public void containerResize(String container, int height, int width) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/resize")
                .query("h", height)
                .query("w", width);
        try (HttpRestClient.Response r = doPOST(req.toString())) {}
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerChanges
     */
    public ContainerChangeResponseItem[] containerChanges(String container, boolean stream) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/changes");
        try (HttpRestClient.Response r = doGET(req.toString())) {
            return gson.fromJson(r.readBody(), ContainerChangeResponseItem[].class);
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerStats
     * FIXME no model in swagger definition.
     */
    // public void containerStats(String container, boolean stream) throws IOException {

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerKill
     */
    public void containerKill(String container, String signal) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/kill")
                .query("signal", signal);
        try (HttpRestClient.Response r = doPOST(req.toString())) {}
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerKill
     */
    public void containerRename(String container, String name) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/rename")
                .query("name", name);
        try (HttpRestClient.Response r = doPOST(req.toString())) {}
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerPause
     */
    public void containerPause(String container) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/pause");
        try (HttpRestClient.Response r = doPOST(req.toString())) {}
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerUnpause
     */
    public void containerUnpause(String container) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/unpause");
        try (HttpRestClient.Response r = doPOST(req.toString())) {}
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerWait
     */
    public ContainerWaitResponse containerWait(String container, WaitCondition condition) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/wait")
                .query("condition", condition.getValue());
        try (HttpRestClient.Response r = doPOST(req.toString())) {
            return gson.fromJson(r.readBody(), ContainerWaitResponse.class);
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerDelete
     */
    public void containerDelete(String container, boolean volumes, boolean links, boolean force) throws IOException {
        Request req = Request("/v", version, "/containers/", container)
                .query("force", force)
                .query("v", volumes)
                .query("link", links);

        try (HttpRestClient.Response r = doDELETE(req.toString())) {}
    }

    public InputStream containerLogs(String container, boolean follow, boolean stdout, boolean stderr, boolean timestamps, int since, String tail) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/logs")
                .query("follow", follow)
                .query("stdout", stdout)
                .query("stderr", stderr)
                .query("since", since)
                .query("tail", tail);

        HttpRestClient.Response r = doGET(req.toString());
        return r.getBody();
    }

    public Streams containerAttach(String id, boolean stdin, boolean stdout, boolean stderr, boolean stream, boolean logs, String detachKeys, boolean tty) throws IOException {
        Request req = Request("/v", version, "/containers/", id, "/attach")
                .query("stdin", stdin)
                .query("stdout", stdout)
                .query("stderr", stderr)
                .query("stream", stream)
                .query("logs", logs)
                .query("detachKeys", detachKeys);

        final Socket socket = getSocket();
        final OutputStream out = socket.getOutputStream();
        final HttpRestClient.Response r = doPOST(socket, req.toString(), new byte[0], Collections.EMPTY_MAP);

        final InputStream s = r.getBody();
        final InputStream streamOut = !tty ? new DockerMultiplexedInputStream(s) : s;

        return new Streams() {

            @Override
            public InputStream stdout() throws IOException {
                return streamOut;
            }

            @Override
            public void redirectStderr(OutputStream stderr) throws IOException {
                if (!tty) {
                    ((DockerMultiplexedInputStream) streamOut).redirectStderr(stderr);
                } else {
                    throw new IOException("stream is not multiplexed");
                }
            }

            @Override
            public OutputStream stdin() throws IOException {
                if (!stdin) throw new IOException("stdin is not attached");
                return out;
            }

            @Override
            public void close() throws Exception {
                socket.close();
            }
        };
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerInspect
     */
    public ContainerInspectResponse containerInspect(String container) throws IOException {
        try (HttpRestClient.Response r = doGET("/v"+version+"/containers/"+container+"/json")) {
            return gson.fromJson(r.readBody(), ContainerInspectResponse.class);
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerExec
     */
    public String containerExec(String container, ExecConfig execConfig) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/exec");
        String spec = gson.toJson(execConfig);
        try (HttpRestClient.Response r = doPOST(req.toString(), spec)) {
            return gson.fromJson(r.readBody(), IdResponse.class).getId();
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerArchiveInfo
     */
    public FileSystemHeaders containerArchiveInfo(String container, String path) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/archive")
                .query("path", path);
        try (HttpRestClient.Response<?> r = doHEAD(req.toString());
             InputStream stream = new ByteArrayInputStream(
                     Base64.getDecoder().decode(
                        r.getHeaders().get("X-Docker-Container-Path-Stat").getBytes(UTF_8)));
            Reader reader = new InputStreamReader(stream)) {
            return gson.fromJson(reader, FileSystemHeaders.class);
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerArchive
     */
    public TarArchiveInputStream containerArchive(String container, String path) throws IOException {
        Request req = Request("/v", version, "/containers/", container, "/archive")
                .query("path", path);
        HttpRestClient.Response r = doGET(req.toString());
        return new TarArchiveInputStream(r.getBody());
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ContainerPrune
     */
    public ContainerPruneResponse containerPrune(ContainersFilters filters) throws IOException {
        Request req = Request("/v", version, "/containers/prune")
                .query("filters", gson.toJson(filters));
        try (HttpRestClient.Response r = doPOST(req.toString())) {
            return gson.fromJson(r.readBody(), ContainerPruneResponse.class);
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ExecStart
     */
    public Streams execStart(String id, boolean detach, boolean tty) throws IOException {
        Request req = Request("/v", version, "/exec/", id, "/start");
        final String json = "{\"Detach\": " + detach + ", \"Tty\": " + tty + "}";
        if (detach) {
            try (HttpRestClient.Response r = doPOST(req.toString(), json)) {
                return null;
            }
        }
        final Socket socket = getSocket();
        final OutputStream out = socket.getOutputStream();
        final Response r = doPOST(socket, req.toString(), json.getBytes(UTF_8), Collections.EMPTY_MAP);
        final DockerMultiplexedInputStream stream = new DockerMultiplexedInputStream(r.getBody());
        return new Streams() {

            @Override
            public InputStream stdout() throws IOException {
                // FIXME https://github.com/moby/moby/issues/35761
                return stream;
            }

            @Override
            public OutputStream stdin() throws IOException {
                return out;
            }

            @Override
            public void redirectStderr(OutputStream stderr) throws IOException {
                stream.redirectStderr(stderr);
            }

            @Override
            public void close() throws Exception {
                socket.close();
            }
        };
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ExecInspect
     */
    public ExecInspectResponse execInspect(String id) throws IOException {
        Request req = Request("/v", version, "/exec/", id, "/json");
        try (HttpRestClient.Response r = doGET(req.toString())) {
            return gson.fromJson(r.readBody(), ExecInspectResponse.class);
        }
    }


    /**
     * "pull" flavor of ImageCreate
     * see https://docs.docker.com/engine/api/v1.40/#operation/ImageCreate
     */
    public void imagePull(String image, String tag, AuthConfig authentication, Consumer<CreateImageInfo> consumer) throws IOException {
        if (tag == null) tag = "latest";
        Request req = Request("/v", version, "/images/create")
                .query("fromImage", image)
                .query("tag", tag);
        Map headers = new HashMap();
        if (authentication != null) {
            headers.put("X-Registry-Auth", Base64.getEncoder().encodeToString(gson.toJson(authentication).getBytes(UTF_8)));
        }
        try (HttpRestClient.Response<ChunkedInputStream> r = doPOST(req.toString(), "", headers);
             final ChunkedInputStream body = r.getBody();
             InputStreamReader reader = new InputStreamReader(body, UTF_8)) {
            while (!body.isEof()) {
                consumer.accept(gson.fromJson(new JsonReader(reader), CreateImageInfo.class));
            }
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ImageInspect
     */
    public Image imageInspect(String image) throws IOException {
        Request req = Request("/v", version, "/images/", image, "/json");
        try (HttpRestClient.Response r = doGET(req.toString())) {
            return gson.fromJson(r.readBody(), Image.class);
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ImageBuild
     * @param buildImageRequest
     */
    public void imageBuild(BuildImageRequest buildImageRequest, AuthConfig authentication, InputStream context, Consumer<BuildInfo> consumer) throws IOException {
        Request req = Request("/v", version, "/build")
                .query("q", buildImageRequest.isQuiet())
                .query("nocache", buildImageRequest.isNocache())
                .query("rm", buildImageRequest.isRm())
                .query("forcerm", buildImageRequest.isForcerm())
                .query("squash", buildImageRequest.isSquash())
                .query("dockerfile", buildImageRequest.getDockerfile())
                .query("t", buildImageRequest.getTag())
                .query("extrahosts", buildImageRequest.getExtrahosts())
                .query("ulimits", buildImageRequest.getUlimits())
                .query("remote", buildImageRequest.getRemote())
                .query("extrahosts", buildImageRequest.getExtrahosts())
                .query("pull", buildImageRequest.getPull())
                .query("memory", buildImageRequest.getMemory())
                .query("memswap", buildImageRequest.getMemswap())
                .query("cpushares", buildImageRequest.getCpushares())
                .query("cpuperiod", buildImageRequest.getCpuperiod())
                .query("cpuquota", buildImageRequest.getCpuquota())
                .query("cpusetcpus", buildImageRequest.getCpusetcpus())
                .query("shmsize", buildImageRequest.getShmsize())
                .query("networkmode", buildImageRequest.getNetworkmode())
                .query("buildargs", buildImageRequest.getBuildargs())
                .query("cachefrom", buildImageRequest.getCachefrom())
                .query("labels", buildImageRequest.getLabels());

        Map headers = new HashMap();
        headers.put("Content-Type", "application/x-tar");
        if (authentication != null) {
            headers.put("X-Registry-Auth", Base64.getEncoder().encodeToString(gson.toJson(authentication).getBytes(UTF_8)));
        }

        try (HttpRestClient.Response r = doPOST(req.toString(), context, headers);
             Reader reader = new InputStreamReader(r.getBody())) {
            while(reader.ready()) {
                 consumer.accept(gson.fromJson(new JsonReader(reader), BuildInfo.class));
             }
         }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/ImagePush
     */
    public void imagePush(String image, String tag, AuthConfig authentication, Consumer<PushImageInfo> consumer) throws IOException {
        if (tag == null) tag = "latest";
        Request req = Request("/v", version, "/images/", image, "/push")
                .query("tag", tag);
        Map headers = new HashMap();
        headers.put("X-Registry-Auth", Base64.getEncoder().encodeToString(gson.toJson(authentication).getBytes(UTF_8)));

        final Socket socket = getSocket();
        try (final Response r = doPOST(socket, req.toString(), "".getBytes(), headers);
             final ChunkedInputStream in = new ChunkedInputStream(socket.getInputStream());
             final InputStreamReader reader = new InputStreamReader(in)) {

            while (!in.isEof()) {
                consumer.accept(gson.fromJson(new JsonReader(reader), PushImageInfo.class));
            }
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/Volumes
     */
    public VolumeListResponse volumeList(VolumeFilters filters) throws IOException {
        Request req = Request("/v", version, "/volumes")
                .query("filters", filters);
        HttpRestClient.Response r = doGET(req.toString());
        return gson.fromJson(r.readBody(), VolumeListResponse.class);
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/VolumesCreate
     */
    public Volume volumeCreate(VolumeConfig volume) throws IOException {
        Request req = Request("/v", version, "/volumes/create");
        HttpRestClient.Response r = doPOST(req.toString(), gson.toJson(volume));
        return gson.fromJson(r.readBody(), Volume.class);
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/VolumeInspect
     */
    public Volume volumeInspect(String name) throws IOException {
        Request req = Request("/v", version, "/volumes/", name);
        try (HttpRestClient.Response r = doGET(req.toString())) {
            return gson.fromJson(r.readBody(), Volume.class);
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/VolumeDelete
     * @return
     */
    public void volumeDelete(String name) throws IOException {
        Request req = Request("/v", version, "/volumes/", name);
        try (HttpRestClient.Response r = doDELETE(req.toString())) {}
    }


    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/NetworkList
     */
    public List<Network> networkList(NetworkFilters filters) throws IOException {
        Request req = Request("/v", version, "/networks")
                .query("filters", filters);
        try (HttpRestClient.Response r = doGET(req.toString())) {
            return gson.fromJson(r.readBody(), NetworkList.class);
        }
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/VolumesCreate
     */
    public Network networkCreate(NetworkConfig volume) throws IOException {
        Request req = Request("/v", version, "/networks/create");
        HttpRestClient.Response r = doPOST(req.toString(), gson.toJson(volume));
        return gson.fromJson(r.readBody(), Network.class);
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/NetworkInspect
     */
    public Network networkInspect(String id) throws IOException {
        Request req = Request("/v", version, "/networks/", id);
        HttpRestClient.Response r = doGET(req.toString());
        return gson.fromJson(r.readBody(), Network.class);
    }

    public void networkConnect(String id, String container) throws IOException {
        networkConnect(id, container, null);
    }
    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/NetworkConnect
     */
    public void networkConnect(String id, String container, EndpointSettings endpoint) throws IOException {
        Request req = Request("/v", version, "/networks/", id, "/connect");
        String body = gson.toJson(new Container().container(container).endpointConfig(endpoint));
        try (HttpRestClient.Response r = doPOST(req.toString(), body)) {}
    }

    public void networkDisconnect(String id, String container) throws IOException {
        networkDisconnect(id, container, false);
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/NetworkDisconnect
     */
    public void networkDisconnect(String id, String container, boolean force) throws IOException {
        Request req = Request("/v", version, "/networks/", id, "/disconnect");
        String body = gson.toJson(new Container1().container(container).force(force));
        try (HttpRestClient.Response r = doPOST(req.toString(), body)) {}
    }

    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/NetworkDelete
     */
    public void networkDelete(String name) throws IOException {
        Request req = Request("/v", version, "/networks/", name);
        try (HttpRestClient.Response r = doDELETE(req.toString())) {}
    }


    /**
     * see https://docs.docker.com/engine/api/v1.40/#operation/SystemEvents
     */
    public void events(EventsFilters filters, String since, String until, EventConsumer consumer) throws IOException {
        Request req = Request("/v", version, "/events")
                .query("filters", filters)
                .query("since", since)
                .query("until", until);
        try (HttpRestClient.Response<ChunkedInputStream> r = doGET(req.toString());
             final ChunkedInputStream body = r.getBody();
             InputStreamReader reader = new InputStreamReader(body, UTF_8)) {
            while (!body.isEof()) {
                boolean done = consumer.accept(gson.fromJson(new JsonReader(reader), SystemEventsResponse.class));
                if (done) return;
            }
        }
    }

    @FunctionalInterface
    public interface EventConsumer {
          boolean accept(SystemEventsResponse event);
    }


    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("DockerClient{");
        sb.append("version='").append(version).append('\'');
        sb.append('}');
        return sb.toString();
    }

    private Request Request(String ... strings)  {
        final Request Request = new Request();
        for (String s : strings) {
            Request.sb.append(s);
        }
        return Request;
    }

    private class Request {
        final StringBuilder sb = new StringBuilder();
        private boolean q;

        private Request query(String name, Filters value) {
            if (value != null) {
                query(name, value.encode(gson));
            }
            return this;
        }

        private Request query(String name, int value) {
            if (value > 0) {
                query(name, (Object) value);
            }
            return this;
        }

        private Request query(String name, Map<?,?> value) {
            if (value != null && value.size() > 0) {
                query(name, gson.toJson(value));
            }
            return this;
        }

        private Request query(String name, Collection<?> value) {
            if (value != null && value.size() > 0) {
                query(name, gson.toJson(value));
            }
            return this;
        }

        private Request query(String name, Object value) {
            if (value == null) {
                return this;
            }
            sb.append( q ? "&": "?");
            sb.append(name);
            sb.append("=");
            sb.append(value);
            q = true;
            return this;
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
