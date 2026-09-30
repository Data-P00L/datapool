#!/bin/bash
set -e

echo "Starting DattaPool infrastructure..."
docker compose down -v
docker compose up -d

echo "Waiting for bitcoind to be ready..."
until docker compose exec -T bitcoind bitcoin-cli -regtest -rpcuser=admin -rpcpassword=password getblockchaininfo > /dev/null 2>&1; do
  sleep 2
done

echo "Creating bitcoind wallet..."
docker compose exec -T bitcoind bitcoin-cli -regtest -rpcuser=admin -rpcpassword=password -named createwallet wallet_name=default load_on_startup=true || true

echo "Mining 150 blocks to activate Taproot..."
ADDR1=$(docker compose exec -T bitcoind bitcoin-cli -regtest -rpcuser=admin -rpcpassword=password getnewaddress)
docker compose exec -T bitcoind bitcoin-cli -regtest -rpcuser=admin -rpcpassword=password generatetoaddress 150 "$ADDR1" > /dev/null

echo "Waiting for lnd to generate macaroons and start up..."
sleep 10

echo "Checking / Initializing LND wallet..."
printf "password123\npassword123\n" | docker compose exec -T lnd lncli --network=regtest create || echo "LND wallet ready or already created."

echo "Waiting for LND to unlock and sync..."
sleep 10

echo "Funding LND wallet..."
LND_ADDR=$(docker compose exec -T lnd lncli --network=regtest newaddress p2tr | python3 -c "import sys, json; print(json.load(sys.stdin).get('address', ''))")
if [ -z "$LND_ADDR" ]; then
    echo "Failed to get LND address."
    exit 1
fi
echo "LND Taproot Address: $LND_ADDR"

docker compose exec -T bitcoind bitcoin-cli -regtest -rpcuser=admin -rpcpassword=password sendtoaddress "$LND_ADDR" 1.0 > /dev/null

echo "Mining 6 blocks to confirm funding..."
docker compose exec -T bitcoind bitcoin-cli -regtest -rpcuser=admin -rpcpassword=password generatetoaddress 6 "$ADDR1" > /dev/null

echo "Waiting for tapd to sync..."
sleep 10

echo "Checking tapd info..."
docker compose exec -T tapd tapcli --network=regtest getinfo

echo "Bootstrap complete!"
