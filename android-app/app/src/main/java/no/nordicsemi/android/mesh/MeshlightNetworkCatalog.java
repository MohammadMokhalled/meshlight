package no.nordicsemi.android.mesh;

import android.content.Context;
import android.annotation.SuppressLint;
import java.util.List;

/** Narrow catalog bridge for the pinned Nordic 3.5.0 database; protocol state remains SDK-owned. */
@SuppressLint("RestrictedApi")
public final class MeshlightNetworkCatalog {
    private MeshlightNetworkCatalog() {}

    public static List<MeshNetwork> list(Context context) {
        return MeshNetworkDb.getDatabase(context).meshNetworkDao().getMeshNetworks();
    }

    public static void select(Context context, String uuid) {
        MeshNetworkDb db = MeshNetworkDb.getDatabase(context);
        db.runInTransaction(() -> {
            for (MeshNetwork network : db.meshNetworkDao().getMeshNetworks()) {
                network.setLastSelected(network.getMeshUUID().equalsIgnoreCase(uuid));
                db.meshNetworkDao().update(network);
            }
        });
    }
}
