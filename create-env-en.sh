#!/bin/bash
set -e

function exit_trap() {
    if [ $? != 0 ]; then
        echo "Command [$BASH_COMMAND] is failed"
        exit 1
    fi
}
trap exit_trap ERR

####################### Setting environment variables for creating Azure resources #######################
# Setting resource group and location

export RESROUCE_GROUP_NAME=Document-Search-Vector1
export DEPLOY_LOCATION=japaneast

# Settings for Azure PostgreSQL Flexible Server (I set it to eastus because I have a build restriction in my environment)
export POSTGRES_INSTALL_LOCATION=eastus
export POSTGRES_SERVER_NAME=yoshiodocumentsearch1
export POSTGRES_USER_NAME=yoterada
export POSTGRES_USER_PASS='!'$(head -c 12 /dev/urandom | base64 | tr -dc '[:alpha:]'| fold -w 8 | head -n 1)$RANDOM
export POSTGRES_DB_NAME=VECTOR_DB
export POSTGRES_TABLE_NAME=DOCUMENT_SEARCH_VECTOR
export PUBLIC_IP=$(curl ifconfig.io -4)

# Settings for Azure Blob Storage
export BLOB_STORAGE_ACCOUNT_NAME=yoshiodocumentsearch1

# Note: If you change the following value, you will also need to change the implementation part of BlobTrigger in Functions.java.
export BLOB_CONTAINER_NAME_FOR_PDF=pdfs

# Settings for Azure Cosmos DB
export COSMOS_DB_ACCOUNT_NAME=yoshiodocumentsearchstatus1
export COSMOS_DB_DB_NAME=documentregistrystatus
export COSMOS_DB_CONTAINER_NAME_FOR_STATUS=status

# Get Azure Subscription ID (no need to change the following if using default subscription)
export SUBSCRIPTION_ID="$(az account list --query "[?isDefault].id" -o tsv)"
####################### Setting environment variables for creating Azure resources #######################


# Create resource group
az group create --name $RESROUCE_GROUP_NAME --location $DEPLOY_LOCATION

# Create Azure PostgreSQL Flexible Server
az postgres flexible-server create --name $POSTGRES_SERVER_NAME \
    -g $RESROUCE_GROUP_NAME \
    --location $POSTGRES_INSTALL_LOCATION \
    --admin-user $POSTGRES_USER_NAME \
    --admin-password $POSTGRES_USER_PASS \
    --version 14 \
    --public-access $PUBLIC_IP --yes
# Azure PostgreSQL Flexible Server Firewall Rule の作成
az postgres flexible-server firewall-rule create \
    -g $RESROUCE_GROUP_NAME \
    -n $POSTGRES_SERVER_NAME \
    -r AllowAllAzureIPs \
    --start-ip-address 0.0.0.0 \
    --end-ip-address 255.255.255.255
# Create Azure PostgreSQL Flexible Server Firewall Rule
az postgres flexible-server db create \
    -g $RESROUCE_GROUP_NAME \
    -s $POSTGRES_SERVER_NAME \
    -d $POSTGRES_DB_NAME
# Create Azure PostgreSQL Flexible Server DB    
az postgres flexible-server parameter set \
    -g $RESROUCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name lc_monetary --value "ja_JP.utf-8"
# Set Japanese settings for Azure PostgreSQL Flexible Server DB   
az postgres flexible-server parameter set \
    -g $RESROUCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name lc_numeric --value "ja_JP.utf-8"
# Set timezone settings for Azure PostgreSQL Flexible Server DB
az postgres flexible-server parameter set \
    -g $RESROUCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name timezone --value "Asia/Tokyo"
# Set extension for Azure PostgreSQL Flexible Server DB
az postgres flexible-server parameter set \
    -g $RESROUCE_GROUP_NAME \
    --server-name $POSTGRES_SERVER_NAME \
    --subscription $SUBSCRIPTION_ID \
    --name azure.extensions --value "VECTOR,UUID-OSSP"


# Create Azure Blob Storage account
az storage account create  -g $RESROUCE_GROUP_NAME --name $BLOB_STORAGE_ACCOUNT_NAME --location $DEPLOY_LOCATION --sku Standard_ZRS  --encryption-services blob
# Get access key for Azure Blob Storage account
export BLOB_ACCOUNT_KEY=$(az storage account keys list --account-name $BLOB_STORAGE_ACCOUNT_NAME -g $RESROUCE_GROUP_NAME --query "[0].value" -o tsv)
# Get connection string for Azure Blob Storage account
export BLOB_CONNECTION_STRING=$(az storage account  show-connection-string -g $RESROUCE_GROUP_NAME --name $BLOB_STORAGE_ACCOUNT_NAME --query "connectionString" --output tsv)

# Create Azure Blob Storage container
az storage container create --account-name $BLOB_STORAGE_ACCOUNT_NAME --name $BLOB_CONTAINER_NAME_FOR_PDF --account-key $BLOB_ACCOUNT_KEY

# Set access permissions for Azure Blob
az storage container set-permission --name $BLOB_CONTAINER_NAME_FOR_PDF --public-access container --account-name $BLOB_STORAGE_ACCOUNT_NAME  --account-key $BLOB_ACCOUNT_KEY


# Create Azure Cosmos DB account, database, container, and get access key
az cosmosdb create -g $RESROUCE_GROUP_NAME --name $COSMOS_DB_ACCOUNT_NAME --kind GlobalDocumentDB --locations regionName=$DEPLOY_LOCATION failoverPriority=0 --default-consistency-level "Session"  
az cosmosdb sql database create --account-name $COSMOS_DB_ACCOUNT_NAME -g $RESROUCE_GROUP_NAME --name $COSMOS_DB_DB_NAME  
export COSMOS_DB_ACCESS_KEY=$(az cosmosdb keys list -g $RESROUCE_GROUP_NAME --name $COSMOS_DB_ACCOUNT_NAME --type keys --query "primaryMasterKey" -o tsv)
az cosmosdb sql container create --account-name $COSMOS_DB_ACCOUNT_NAME -g $RESROUCE_GROUP_NAME --database-name $COSMOS_DB_DB_NAME --name $COSMOS_DB_CONTAINER_NAME_FOR_STATUS --partition-key-path "/id"  --throughput 400  --idx ./cosmos-index-policy.json


echo "########## Write the following content in the local.settings.json file ##########"
echo "-----------------------------------------------------------------------------"
echo "# Set environment settings related to Azure"
echo ""
echo "\"AzureWebJobsStorage\": \"$BLOB_CONNECTION_STRING\","
echo "\"AzurePostgresqlJdbcurl\": \"jdbc:postgresql://$POSTGRES_SERVER_NAME.postgres.database.azure.com:5432/$POSTGRES_DB_NAME?sslmode=require\","
echo "\"AzurePostgresqlUser\": \"$POSTGRES_USER_NAME\","
echo "\"AzurePostgresqlPassword\": \"$POSTGRES_USER_PASS\","
echo "\"AzurePostgresqlDbTableName\": \"$POSTGRES_TABLE_NAME\","
echo "\"AzureBlobstorageName\": \"$BLOB_STORAGE_ACCOUNT_NAME\","
echo "\"AzureBlobstorageContainerName\": \"$BLOB_CONTAINER_NAME_FOR_PDF\","
echo "\"AzureCosmosDbEndpoint\": \"https://$COSMOS_DB_ACCOUNT_NAME.documents.azure.com:443/\","
echo "\"AzureCosmosDbKey\": \"$COSMOS_DB_ACCESS_KEY\","
echo "\"AzureCosmosDbDatabaseName\": \"$COSMOS_DB_DB_NAME\","
echo "\"AzureCosmosDbContainerName\": \"$COSMOS_DB_CONTAINER_NAME_FOR_STATUS\","
echo "\"AzureOpenaiUrl\": \"https://YOUR_OPENAI.openai.azure.com\","
echo "\"AzureOpenaiModelName\": \"gpt-4\","
echo "\"AzureOpenaiApiKey\": \"YOUR_OPENAI_ACCESS_KEY\","
echo "-----------------------------------------------------------------------------"
echo ""
echo "### Write the following content in a separate Spring Boot implementation's application.properties ###"
echo "-----------------------------------------------------------------------------"
echo ""
echo "# Set connection information related to Azure PostgreSQL"
echo ""
echo "azure.postgresql.jdbcurl=jdbc:postgresql://$POSTGRES_SERVER_NAME.postgres.database.azure.com:5432/$POSTGRES_DB_NAME?sslmode=require"
echo "azure.postgresql.user=$POSTGRES_USER_NAME"
echo "azure.postgresql.password=$POSTGRES_USER_PASS"
echo "azure.postgresql.db.table.name=$POSTGRES_TABLE_NAME"
echo ""
echo "# Set settings related to Blob"
echo ""
echo "azure.blobstorage.name=$BLOB_STORAGE_ACCOUNT_NAME"
echo "azure.blobstorage.container.name=$BLOB_CONTAINER_NAME_FOR_PDF"
echo ""
echo "# Set settings related to Azure Cosmos DB"
echo ""
echo "azure.cosmos.db.endpoint=https://$COSMOS_DB_ACCOUNT_NAME.documents.azure.com:443"
echo "azure.cosmos.db.key=$COSMOS_DB_ACCESS_KEY"
echo "azure.cosmos.db.database.name=$COSMOS_DB_DB_NAME"
echo "azure.cosmos.db.container.name=$COSMOS_DB_CONTAINER_NAME_FOR_STATUS"
echo ""
echo "# Set settings related to Azure OpenAI"
echo "# Please create your own OpenAI instance from Azure Portal GUI not CLI"
echo ""
echo "azure.openai.url=https://YOUR_OPENAI.openai.azure.com"
echo "azure.openai.model.name=gpt-4"
echo "azure.openai.api.key=$YOUR_OPENAI_ACCESS_KEY"
echo "-----------------------------------------------------------------------------"
echo ""
echo "# After creating PostgreSQL, connect with the following command"
echo "-----------------------------------------------------------------------------"
echo "> psql -U $POSTGRES_USER_NAME -d $POSTGRES_DB_NAME \\"
echo "     -h $POSTGRES_SERVER_NAME.postgres.database.azure.com"
echo ""
echo " Auto-generated PostgreSQL password: $POSTGRES_USER_PASS"
echo ""
echo "# After connecting to PostgreSQL, execute the following command"
echo "-------------------------------------------------------------"
echo "$POSTGRES_DB_NAME=> CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\";"
echo "$POSTGRES_DB_NAME=> CREATE EXTENSION IF NOT EXISTS \"vector\";"
echo ""
echo "# Finally, execute the following command to create the TABLE"
echo ""
echo "$POSTGRES_DB_NAME=> CREATE TABLE IF NOT EXISTS $POSTGRES_TABLE_NAME"
echo "                 (id uuid, embedding VECTOR(1536),"
echo "                  origntext varchar(8192), fileName varchar(2048),"
echo "                  pageNumber integer, PRIMARY KEY (id));"
echo "-----------------------------------------------------------------------------"


