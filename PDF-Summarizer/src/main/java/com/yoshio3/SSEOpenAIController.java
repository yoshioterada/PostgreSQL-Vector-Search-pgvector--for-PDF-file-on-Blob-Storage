package com.yoshio3;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import com.azure.ai.openai.OpenAIAsyncClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatMessage;
import com.azure.ai.openai.models.ChatMessageDelta;
import com.azure.ai.openai.models.ChatRole;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.core.credential.AzureKeyCredential;
import com.google.gson.Gson;
import com.yoshio3.entities.CreateAreaInHTML;
import com.yoshio3.entities.CreateLinkInHTML;
import com.yoshio3.entities.CreateMessageInHTML;
import com.yoshio3.entities.DocumentSummarizer;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

@Controller
@Component
public class SSEOpenAIController {

    private final Logger LOGGER = LoggerFactory.getLogger(SSEOpenAIController.class);

    // Azure OpenAI Instance URL
    @Value("${azure.openai.url}")
    private String OPENAI_URL;

    // Name of Model
    @Value("${azure.openai.model.name}")
    private String OPENAI_MODEL_NAME;

    // Azure OpenAI API Key
    @Value("${azure.openai.api.key}")
    private String OPENAI_API_KEY;

    @Value("${azure.postgresql.jdbcurl}")
    private String POSTGRESQL_JDBC_URL;

    @Value("${azure.postgresql.user}")
    private String POSTGRESQL_USER;

    @Value("${azure.postgresql.password}")
    private String POSTGRESQL_PASSWORD;

    @Value("${azure.postgresql.db.table.name}")
    private String POSTGRESQL_TABLE_NAME;

    @Value("${azure.blobstorage.name}")
    private String BLOB_STORAGE_NAME;

    @Value("${azure.blobstorage.container.name}")
    private String BLOB_STORAGE_CONTAINER_NAME;

    // Maximum number of results to be returned by the search process
    private static final int MAX_RESULT = 5;

    private static final String TEXT_EMBEDDING_ADA = "text-embedding-ada-002";

    private final static String SYSTEM_DEFINITION = """
                このシステムは、ドキュメントを管理するためのシステムです。
                ユーザから入力された内容に該当するドキュメントを検索し、
                要約してその内容をユーザに提供します。
            """;

    // クライアントからのリクエストを受け付けるためのSinks (1対1 で送受信するためのSinks)
    private static Map<UUID, Sinks.Many<String>> userSinks;

    // Static Initializer
    static {
        userSinks = new ConcurrentHashMap<>();
    }

    @Autowired
    private CosmosDBUtil cosmosDBUtil;

    private OpenAIAsyncClient client;

    @PostConstruct
    public void init() {
        client = new OpenAIClientBuilder().endpoint(OPENAI_URL)
                .credential(new AzureKeyCredential(OPENAI_API_KEY)).buildAsyncClient();
    }

    // Return index.html
    @GetMapping("/")
    public String index() {
        return "index";
    }

    // When accessing the page, create a UUID for each client (for 1-on-1 sending
    // and receiving)
    // This part of the process is unnecessary if you want to update the same
    // content (1-to-many) like a chat
    @GetMapping(path = "/openai-gpt4-sse-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public Flux<String> sseStream(@RequestParam UUID userId) {
        Sinks.Many<String> userSink = getUserSink(userId);
        if (userSink == null) {
            userSink = createUserSink(userId);
        }
        LOGGER.trace("USER ID IS ADDED: {}}", userId);
        return userSink.asFlux().delayElements(Duration.ofMillis(10));
    }

    @PostMapping("/openai-gpt4-sse-submit")
    @ResponseBody
    public void openaiGpt4Sse(@RequestBody String inputText, @RequestParam UUID userId) {
        var userSink = getUserSink(userId);
        LOGGER.debug("InputText --------------: {}", inputText);
        // ユーザからの入力を受け取り、PostgreSQL の Vector DB からドキュメントを検索
        findMostSimilarString(inputText).subscribe(findMostSimilarString -> {
            // ドキュメントの検索結果を元に、OpenAI による要約を実施し結果をクライアントに送信
            findMostSimilarString.forEach(docSummary -> {
                requestOpenAIToGetSummaryAndSendMessageToClient(docSummary, inputText, userSink);
            });
        });
    }

    // チャットに送信するメッセージを作成
    private String createChatMessages(DocumentSummarizer docSummary, String inputText) {
        return String.format(
                "\"\"\" %s \"\"\" \n\nこちらのドキュメントの中から \"%s\" に関して説明している箇所を抜き出してください。",
                docSummary.origntext(), inputText);
    }

    // OpenAI に送信し検索結果のドキュメントの要約を作成し、クライアントに Stream で送信する
    private void requestOpenAIToGetSummaryAndSendMessageToClient(DocumentSummarizer docSummary, String inputText, Sinks.Many<String> userSink) {
        LOGGER.debug("Origin --------------: {}", docSummary.origntext());

        var input = createChatMessages(docSummary, inputText);
        LOGGER.debug("User Sink: {}", userSink);
        LOGGER.debug(input);
        var chatMessages = createMessages(input);
        LOGGER.debug("OpenAI Model : {}", OPENAI_MODEL_NAME);

        // OpenAI にリクエストを送信し、結果をクライアントに送信する
        client.getChatCompletionsStream(OPENAI_MODEL_NAME, new ChatCompletionsOptions(chatMessages))
                .doOnSubscribe(subscription -> {
                    // HTML の中で、リンクと結果の文字列を表示するための DIV エリアを作成する為のリクエスト・イベントを送信
                    sendCreateAreaEvent(userSink, docSummary);
                    // HTML の中で、リンクを表示する為のリクエスト・イベントを送信
                    sendCreateLinkEvent(userSink, docSummary);
                })
                .subscribe(chatCompletions -> {
                    // OpenAI からの結果をクライアントに Streaming で送信
                    sendChatCompletionMessages(userSink, docSummary, chatCompletions, inputText);
                }, error -> {
                    LOGGER.error("Error Occurred: {}", error.getMessage());
                    userSink.tryEmitError(error);
                }, () -> {
                    LOGGER.debug("Completed");
                });
    }

    // HTML の中で、リンクと結果の文字列を表示するための DIV エリアを作成する為のリクエスト・イベントを送信する
    private void sendCreateAreaEvent(Sinks.Many<String> userSink, DocumentSummarizer docSummary) {
        var documentID = docSummary.id().toString();
        var createArea = new CreateAreaInHTML("create", documentID);
        var gson = new Gson();
        var jsonCreateArea = gson.toJson(createArea);
        LOGGER.debug("jsonCreateArea: {}", jsonCreateArea);
        userSink.tryEmitNext(jsonCreateArea);
        // wait few mill seconds
        intervalToSendClient();
    }

    // HTML の中で、リンクを表示する為のリクエスト・イベントを送信する
    private void sendCreateLinkEvent(Sinks.Many<String> userSink, DocumentSummarizer docSummary) {
        var fileName = docSummary.filename();
        var pageNumber = docSummary.pageNumber();
        var documentID = docSummary.id().toString();
        // Create URL for Blob Storage
        var URL = "https://" + BLOB_STORAGE_NAME + ".blob.core.windows.net/"
                + BLOB_STORAGE_CONTAINER_NAME + "/" + fileName + "#page="
                + docSummary.pageNumber();

        var createLinkRecord = new CreateLinkInHTML("createLink", documentID, URL, pageNumber, fileName);
        var gson = new Gson();
        var jsonLink = gson.toJson(createLinkRecord);
        LOGGER.debug("JSON Create Link: {}", jsonLink);
        userSink.tryEmitNext(jsonLink);
        // wait few mill seconds
        intervalToSendClient();
    }

    // HTML の中で、メッセージを１文字づつ表示する為のリクエスト・イベントを送信する
    private void sendChatCompletionMessages(Sinks.Many<String> userSink, DocumentSummarizer docSummary,
            ChatCompletions chatCompletions, String inputText) {
        var documentID = docSummary.id().toString();

        chatCompletions.getChoices().stream().map(ChatChoice::getDelta)
                .map(ChatMessageDelta::getContent)
                .filter(content -> content != null)
                .forEach(content -> {
                    if (content.contains(" ")) {
                        content = content.replace(" ", "<SPECIAL_WHITE_SPACE>");
                    }
                    LOGGER.debug(content);
                    var createMessage = new CreateMessageInHTML("addMessage", documentID, content);
                    var gson = new Gson();
                    var jsonMessage = gson.toJson(createMessage);
                    LOGGER.debug("JSON Message: {}", jsonMessage);
                    var result = userSink.tryEmitNext(jsonMessage);
                    showDetailErrorReasonForSSE(result, content, inputText);
                    // wait few mill seconds
                    intervalToSendClient();
                });
    }

    @GetMapping("/listAllRegisteredContents")
    public String listAllRegisteredContents(Model model) {
        // CosmosDB から全てのドキュメントを取得し、Web ページに表示するため、Model に追加
        // 将来的にはページング処理を実装する必要ある
        model.addAttribute("list", cosmosDBUtil.getAllRegisteredDocuments());
        return "listAllRegisteredContents";
    }

    @GetMapping("/listAllFailedContents")
    public String listAllFailedContents(Model model) {
        // CosmosDB から全てのドキュメントを取得し、Web ページに表示するため、Model に追加
        // 将来的にはページング処理を実装する必要ある
        model.addAttribute("list", cosmosDBUtil.getAllFailedDocuments());
        return "listAllFailedContents";
    }

    // Create Sinks for accessed User
    private Sinks.Many<String> createUserSink(UUID userId) {
        Sinks.Many<String> userSink = Sinks.many().multicast().directBestEffort();
        userSinks.put(userId, userSink);
        LOGGER.debug("User ID: {} User Sink: {} is Added.", userId, userSink);
        return userSink;
    }

    // Get User Sinks
    private Sinks.Many<String> getUserSink(UUID userId) {
        return userSinks.get(userId);
    }

    /**
     * Crete ChatMessage list
     */
    private List<ChatMessage> createMessages(String userInput) {
        List<ChatMessage> chatMessages = new ArrayList<>();
        chatMessages.add(new ChatMessage(ChatRole.SYSTEM).setContent(SYSTEM_DEFINITION));
        chatMessages.add(new ChatMessage(ChatRole.USER).setContent(userInput));
        return chatMessages;
    }

    // Show Error Message when SSE failed to send the message
    private void showDetailErrorReasonForSSE(EmitResult result, String returnValue, String data) {
        if (result.isFailure()) {
            LOGGER.error("Failure: {}", returnValue + " " + data);
            if (result == EmitResult.FAIL_OVERFLOW) {
                LOGGER.error("Overflow: {}", returnValue + " " + data);
            } else if (result == EmitResult.FAIL_NON_SERIALIZED) {
                LOGGER.error("Non-serialized: {}", returnValue + " " + data);
            } else if (result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                LOGGER.error("Zero subscriber: {}", returnValue + " " + data);
            } else if (result == EmitResult.FAIL_TERMINATED) {
                LOGGER.error("Terminated: {}", returnValue + " " + data);
            } else if (result == EmitResult.FAIL_CANCELLED) {
                LOGGER.error("Cancelled: {}", returnValue + " " + data);
            }
        }
    }

    // 現時点では、Spring Data JPA を利用できない
    // 理由：Spring Data JPA では Native Query を利用したとしても、
    // PostgreSQL の vector 型を扱うことができなかったため JDBC を利用
    public Mono<List<DocumentSummarizer>> findMostSimilarString(String inputData) {
        EmbeddingsOptions embeddingsOptions = new EmbeddingsOptions(Arrays.asList(inputData));

        return client.getEmbeddings(TEXT_EMBEDDING_ADA, embeddingsOptions)
                .flatMap(embeddings -> {
                    List<DocumentSummarizer> docSummaryList = new ArrayList<>();
                    List<Double> embedding = embeddings.getData().stream().findFirst().get().getEmbedding();

                    try (var connection = DriverManager.getConnection(POSTGRESQL_JDBC_URL,
                            POSTGRESQL_USER, POSTGRESQL_PASSWORD)) {
                        String array = embedding.toString();
                        LOGGER.debug("Embedding: \n{}", array);
                        // Vector での検索 (LIMIT を変更し複数件取得可能だが、非同期 Non-Blocking の場合には回答が混ざる)

                        String querySql = "SELECT id,origntext,filename,pageNumber FROM " + POSTGRESQL_TABLE_NAME
                                + " ORDER BY embedding <-> ?::vector LIMIT " + MAX_RESULT + ";";

                        PreparedStatement queryStatement = connection.prepareStatement(querySql);
                        queryStatement.setString(1, array);
                        ResultSet resultSet = queryStatement.executeQuery();
                        LOGGER.debug("resultSet: {}", resultSet);
                        while (resultSet.next()) {
                            DocumentSummarizer documentSummarizer = new DocumentSummarizer(
                                    UUID.fromString(resultSet.getString("id")),
                                    null,
                                    resultSet.getString("origntext"),
                                    resultSet.getString("filename"),
                                    resultSet.getInt("pageNumber"));
                            docSummaryList.add(documentSummarizer);
                            LOGGER.debug("DocumentSummarizer: {}", documentSummarizer);
                        }
                    } catch (SQLException e) {
                        LOGGER.error("Connection failure: {}", e.getMessage());
                    }
                    return Mono.just(docSummaryList);
                });
    }

    private void intervalToSendClient() {
        try {
            TimeUnit.MILLISECONDS.sleep(20);
        } catch (InterruptedException e) {
            LOGGER.error("Error Occurred: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
