# Allow the driver to authenticate and obtain a client token.
# (Covered by Vault's built-in "default" policy; included here explicitly
#  for deployments that restrict or remove the default policy.)
path "auth/token/lookup-self" {
  capabilities = ["read"]
}
path "auth/token/revoke-self" {
  capabilities = ["update"]
}

# KV v2: allow reading the specific secret path.
# The data/ segment is required for KV v2 read operations.
path "secret/data/apps/database" {
  capabilities = ["read"]
}

# KV v1: allow reading the specific secret path.
# Remove or comment out when using KV v2 only.
# path "secret/apps/database" {
#   capabilities = ["read"]
# }
