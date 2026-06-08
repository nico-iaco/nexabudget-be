package it.iacovelli.nexabudgetbe.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GocardlessGetAccountsResponse extends GocardlessBaseResponse<GocardlessGetAccounts> {
    /** Codice raw GoCardless: EX (expired), RJ (rejected), SU (suspended), CR/GC/UA/SA/GA (pending)… */
    private String requisitionStatus;
    /** Categoria leggibile: expired | rejected | suspended | pending | unknown */
    private String linkedStatus;
    /** true = serve una nuova requisition via /create-web-token */
    private Boolean renewable;
    @JsonProperty("error_type")
    private String errorType;
    @JsonProperty("error_code")
    private String errorCode;
    private String reason;
}
