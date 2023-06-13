package com.yoshio3;

import com.microsoft.azure.functions.annotation.*;
import com.yoshio3.models.CosmosDBDocumentStatus;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.core.credential.AzureKeyCredential;
import com.microsoft.azure.functions.*;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;


public class Function {
    // Azure OpenAI の API キー
    private static final String OPENAI_API_KEY;
    // Azure OpenAI のインスタンスの URL
    private static final String OPENAI_URL;

    // Azure PostgreSQL の JDBC URL
    private static final String POSTGRESQL_JDBC_URL;
    // Azure PostgreSQL のユーザー名
    private static final String POSTGRESQL_USER;
    // Azure PostgreSQL のパスワード
    private static final String POSTGRESQL_PASSWORD;
    // Azure PostgreSQL のテーブル名
    private static final String POSTGRESQL_TABLE_NAME;
    // １ページに含まれる文字数の上限（これを超える場合はページを分割して処理する）
    private static final int MAX_SEPARATE_TOKEN_LENGTH = 7500;
    // Azure OpenAI のクライアント・インスタンス
    private OpenAIClient client;

    // Azure OpenAI の呼び出し間隔（ミリ秒）
    private final static int OPENAI_INVOCATION_INTERVAL = 20;

    // Azure OpenAI の呼び出しリトライ回数
    private static final int MAX_OPENAI_INVOCATION_RETRY_COUNT = 3;
    // Azure Cosmos DB のクライアント・インスタンス
    CosmosDBUtil cosmosDBUtil;

    static {
        OPENAI_API_KEY = System.getenv("AzureOpenaiApiKey");
        OPENAI_URL = System.getenv("AzureOpenaiUrl");

        POSTGRESQL_JDBC_URL = System.getenv("AzurePostgresqlJdbcurl");
        POSTGRESQL_USER = System.getenv("AzurePostgresqlUser");
        POSTGRESQL_PASSWORD = System.getenv("AzurePostgresqlPassword");
        POSTGRESQL_TABLE_NAME = System.getenv("AzurePostgresqlDbTableName");
    }

    public Function() {
        client = new OpenAIClientBuilder().credential(new AzureKeyCredential(OPENAI_API_KEY))
                .endpoint(OPENAI_URL).buildClient();
        cosmosDBUtil = new CosmosDBUtil();
    }

    // 注意：applications.properties で "azure.blobstorage.container.name=pdfs" を変更した場合は
    // @BlobTrigger, @BlobInput の path も変更する必要があります。 デフォルト値：(pdfs/{name})
    // 理由は、path で指定できる値は、constants で定義されているものだけで、プロパティから取得することはできないためです。
    @FunctionName("ProcessUploadedFile")
    @StorageAccount("AzureWebJobsStorage")
    public void run(
            @BlobTrigger(
                    name = "content", path = "pdfs/{name}", dataType = "binary") byte[] content,
            @BindingName("name") String fileName,
            @BlobInput(name = "inputBlob", path = "pdfs/{name}",
                    dataType = "binary") byte[] inputBlob,
            final ExecutionContext context) throws UnsupportedEncodingException {
        String encodedFileName = URLEncoder.encode(fileName, "UTF-8");
        context.getLogger().info(encodedFileName);

        if (fileName.endsWith(".pdf")) {
            var extractPDFtoTextByPage = extractPDFtoTextByPage(content, context);
            extractPDFtoTextByPage.forEach(pageInfo -> insertDataToPostgreSQL(pageInfo.text(),
                    context, fileName, pageInfo.pageNumber()));
        }
    }

    private List<PageInfo> extractPDFtoTextByPage(byte[] content, ExecutionContext context) {
        // String pdfFilePath = "/tmp/azure-app-service.pdf";
        List<PageInfo> allPages = new ArrayList<>();

        try (PDDocument document = PDDocument.load(content)) {
            // try (PDDocument document = PDDocument.load(new File(pdfFilePath))) {
            PDFTextStripper textStripper = new PDFTextStripper();
            int numberOfPages = document.getNumberOfPages();

            // PDF ファイルのページ数分ループ
            IntStream.rangeClosed(1, numberOfPages).forEach(pageNumber -> {
                try {
                    textStripper.setStartPage(pageNumber);
                    textStripper.setEndPage(pageNumber);
                    String pageText = textStripper.getText(document);
                    // 改行コードを空白文字に置き換え
                    pageText = pageText.replace("\n", " ");
                    pageText = pageText.replaceAll("\\s{2,}", " ");

                    // 1 ページのテキストが 7500 文字を超える場合は分割する
                    if (pageText.length() > MAX_SEPARATE_TOKEN_LENGTH) {
                        context.getLogger().fine("Split text: " + pageText.length());
                        List<String> splitText = splitText(pageText, MAX_SEPARATE_TOKEN_LENGTH);
                        splitText.forEach(text -> {
                            PageInfo pageInfo = new PageInfo(pageNumber, text);
                            allPages.add(pageInfo);
                        });
                    } else {
                        PageInfo pageInfo = new PageInfo(pageNumber, pageText);
                        allPages.add(pageInfo);
                    }
                } catch (IOException e) {
                    context.getLogger()
                            .severe("Error while extracting text from PDF: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        } catch (IOException e) {
            context.getLogger().severe("Error while extracting text from PDF: " + e.getMessage());
            e.printStackTrace();
        }
        return allPages;
    }

    // PostgreSQL に Vector データを挿入するサンプル (text-embedding-ada-001)
    private void insertDataToPostgreSQL(String originText, ExecutionContext context,
            String fileName, int pageNumber) {
        UUID uuid = UUID.randomUUID();
        String uuidString = uuid.toString();
        try {
            cosmosDBUtil
                    .createDocument(uuidString, fileName,
                            CosmosDBDocumentStatus.PAGE_SEPARATE_FINISHED, pageNumber, context)
                    .block();

            // OpenAI Text Embedding(text-embedding-ada-002) を呼び出しベクター配列を取得
            List<Double> embedding = invokeTextEmbedding(uuidString, originText, context);
            cosmosDBUtil.updateStatus(uuidString, CosmosDBDocumentStatus.FINISH_OAI_INVOCATION,
                    context);

            // ベクター配列を PostgreSQL に挿入
            var insertSql = "INSERT INTO " + POSTGRESQL_TABLE_NAME
                    + " (id, embedding, origntext, fileName, pageNumber) VALUES (?, ?::vector, ?, ?, ?)";
            try (var connection = DriverManager.getConnection(POSTGRESQL_JDBC_URL, POSTGRESQL_USER,
                    POSTGRESQL_PASSWORD);
                    PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
                insertStatement.setObject(1, uuid);
                insertStatement.setArray(2,
                        connection.createArrayOf("double", embedding.toArray()));
                insertStatement.setString(3, originText);
                insertStatement.setString(4, fileName);
                insertStatement.setInt(5, pageNumber);
                insertStatement.executeUpdate();
                cosmosDBUtil.updateStatus(uuidString, CosmosDBDocumentStatus.FINISH_DB_INSERTION,
                        context);
            }
            // 暫定的対応：短時間に大量のリクエストを送るとエラーになるため、10秒間スリープ
            sleep();
        } catch (Exception e) {
            context.getLogger()
                    .severe("Error while inserting data to PostgreSQL: " + e.getMessage());
            cosmosDBUtil.updateStatus(uuidString, CosmosDBDocumentStatus.FAILED_DB_INSERTION,
                    context);
            Thread.currentThread().interrupt();
        }
        cosmosDBUtil.updateStatus(uuidString, CosmosDBDocumentStatus.COMPLETED, context);
    }

    /**
     * テキスト・エンべディングの検証サンプル (text-embedding-ada-002)
     */
    private List<Double> invokeTextEmbedding(String uuid, String originalText,
            ExecutionContext context) {
        List<Double> embedding = new ArrayList<>();
        var embeddingsOptions = new EmbeddingsOptions(Arrays.asList(originalText));

        int retryCount = 0;
        while (retryCount < MAX_OPENAI_INVOCATION_RETRY_COUNT) {
            try {
                // OpenAI API を呼び出し
                var result = client.getEmbeddings("text-embedding-ada-002", embeddingsOptions);
                // 利用状況を取得（使用したトークン数）
                var usage = result.getUsage();
                context.getLogger().info("Number of Prompt Token: " + usage.getPromptTokens()
                        + "Number of Total Token: " + usage.getTotalTokens());
                // ベクター配列を取得
                var findFirst = result.getData().stream().findFirst();
                if (findFirst.isPresent()) {
                    embedding.addAll(findFirst.get().getEmbedding());
                }
                break;
            } catch (Exception e) {
                context.getLogger().severe("Error while invoking OpenAI: " + e.getMessage());
                cosmosDBUtil.updateStatus(uuid, CosmosDBDocumentStatus.RETRY_OAI_INVOCATION,
                        context);
                retryCount++;
                sleep();
            }
        }
        return embedding;
    }

    // 入力文字列を7500文字前後で分割し、句読点で区切られた部分で分割を行います。
    // トークンは 8192 で 8000 で分割した経験上では命令を出す際にオーバフローすることがあるため
    private List<String> splitText(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        int textLength = text.length();

        while (textLength > maxLength) {
            int splitIndex = findSplitIndex(text, maxLength);
            chunks.add(text.substring(0, splitIndex));
            text = text.substring(splitIndex);
            textLength = text.length();
        }
        chunks.add(text);
        return chunks;
    }

    // 入力文字列を7500文字の前後で分割し、区切り文字（。？！など）で分割を行います。
    // また、適切な区切り文字が見つからない場合、単純に7500文字ごとに分割されます。
    private int findSplitIndex(String text, int maxLength) {
        // 7200-7500の文字の範囲で区切り文字を探す
        int start = maxLength - 300;
        int splitIndex = maxLength;
        while (splitIndex > start) {
            char c = text.charAt(splitIndex);
            if (isPunctuation(c)) {
                break;
            }
            splitIndex--;
        }
        if (splitIndex == 0) {
            splitIndex = maxLength;
        }
        return splitIndex;
    }

    // 区切り文字の判定
    private boolean isPunctuation(char c) {
        return c == '.' || c == '。' || c == ';' || c == '；' || c == '!' || c == '！' || c == '?'
                || c == '？';
    }

    private void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(OPENAI_INVOCATION_INTERVAL);
        } catch (InterruptedException interruptedException) {
            interruptedException.printStackTrace();
            Thread.currentThread().interrupt();
        }
    }

}
