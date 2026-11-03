package com.jurislpo.jurisone.crypto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** {@code payslips.snapshot_enc}: the payslip's JSON (salary figures) stored encrypted. Old rows still hold the plain {@code snapshot} JSON until the backfill clears it. */
@Component
public class PayslipSnapshotCrypto {

    private final PiiCrypto piiCrypto;

    public PayslipSnapshotCrypto(PiiCrypto piiCrypto) {
        this.piiCrypto = piiCrypto;
    }

    public String encode(String snapshotJson) {
        return piiCrypto.encrypt(snapshotJson);
    }

    /** The JSON text, from the encrypted column when there is one, else from the old plain column. */
    public String read(String plainSnapshot, String snapshotEnc) {
        return snapshotEnc == null ? plainSnapshot : piiCrypto.decrypt(snapshotEnc);
    }

    /** The row (which must select {@code snapshot} as text and {@code snapshot_enc}) with {@code snapshot} filled in and {@code snapshot_enc} removed. */
    public Map<String, Object> withSnapshot(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("snapshot", read((String) row.get("snapshot"), (String) row.get("snapshot_enc")));
        out.remove("snapshot_enc");
        return out;
    }

    public List<Map<String, Object>> withSnapshots(List<Map<String, Object>> rows) {
        return rows.stream().map(this::withSnapshot).toList();
    }
}
