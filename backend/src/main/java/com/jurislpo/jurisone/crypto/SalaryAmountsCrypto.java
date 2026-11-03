package com.jurislpo.jurisone.crypto;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The four monthly salary amounts (paise) of a salary structure, stored as one encrypted value in {@code salary_structures.amounts_enc}
 * ("basic,hra,lta,special" inside the usual {@code v1.} encryption). Old rows still hold the plain integer columns until the backfill clears them.
 */
@Component
public class SalaryAmountsCrypto {

    private final PiiCrypto piiCrypto;

    public SalaryAmountsCrypto(PiiCrypto piiCrypto) {
        this.piiCrypto = piiCrypto;
    }

    public String encode(long basic, long hra, long lta, long special) {
        return piiCrypto.encrypt(basic + "," + hra + "," + lta + "," + special);
    }

    /** {basic, hra, lta, special} from the encrypted value, or from the old plain columns when there is none. */
    public long[] amounts(String amountsEnc, Number basic, Number hra, Number lta, Number special) {
        if (amountsEnc == null) {
            if (basic == null || hra == null || lta == null || special == null) throw new IllegalStateException("A salary structure has no amounts");
            return new long[] {basic.longValue(), hra.longValue(), lta.longValue(), special.longValue()};
        }
        String[] parts = piiCrypto.decrypt(amountsEnc).split(",", -1);
        if (parts.length != 4) throw new IllegalStateException("Unrecognised salary amounts");
        return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]), Long.parseLong(parts[3])};
    }

    public long gross(String amountsEnc, Number basic, Number hra, Number lta, Number special) {
        long[] values = amounts(amountsEnc, basic, hra, lta, special);
        return values[0] + values[1] + values[2] + values[3];
    }

    /** The row (which must also select {@code amounts_enc}) with basic, hra, lta and special filled in as plain numbers and {@code amounts_enc} removed. */
    public Map<String, Object> withAmounts(Map<String, Object> row) {
        long[] values = amounts((String) row.get("amounts_enc"), (Number) row.get("basic"), (Number) row.get("hra"), (Number) row.get("lta"), (Number) row.get("special"));
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.remove("amounts_enc");
        out.put("basic", values[0]);
        out.put("hra", values[1]);
        out.put("lta", values[2]);
        out.put("special", values[3]);
        return out;
    }
}
