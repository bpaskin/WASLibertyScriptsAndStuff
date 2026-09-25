package com.ibm.example.vault.was;

import com.ibm.websphere.appprofile.accessintent.AccessIntent;
import com.ibm.websphere.rsadapter.GenericDataStoreHelper;
import java.sql.Connection;
import java.util.Properties;
import javax.resource.ResourceException;

/** Traditional WebSphere defaults for the non-transactional Vault JDBC source. */
public final class VaultDataStoreHelper extends GenericDataStoreHelper {
    public VaultDataStoreHelper(Properties properties) {
        super(properties);
    }

    /**
     * Generic JDBC defaults request transactional isolation during allocation.
     * Vault secret reads have no transactions, so ordinary lookups require NONE.
     * Explicit access-intent policies are rejected rather than weakened.
     */
    @Override
    public int getIsolationLevel(AccessIntent intent) throws ResourceException {
        if (intent != null) {
            throw new ResourceException(
                    "Vault secret reads do not support access-intent transaction policies");
        }
        return Connection.TRANSACTION_NONE;
    }
}
