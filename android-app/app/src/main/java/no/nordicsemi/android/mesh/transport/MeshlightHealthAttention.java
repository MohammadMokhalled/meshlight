package no.nordicsemi.android.mesh.transport;

import no.nordicsemi.android.mesh.ApplicationKey;
import no.nordicsemi.android.mesh.utils.SecureUtils;

public final class MeshlightHealthAttention extends ApplicationMessage {
    public MeshlightHealthAttention(ApplicationKey key, int seconds) {
        super(key);
        if (seconds < 0 || seconds > 255) throw new IllegalArgumentException("Invalid attention duration");
        mParameters = new byte[] { (byte) seconds };
        assembleMessageParameters();
    }

    @Override public int getOpCode() { return 0x8006; }
    @Override void assembleMessageParameters() { mAid = SecureUtils.calculateK4(mAppKey.getKey()); }
}
