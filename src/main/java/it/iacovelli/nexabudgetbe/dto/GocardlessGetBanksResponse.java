package it.iacovelli.nexabudgetbe.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class GocardlessGetBanksResponse extends GocardlessBaseResponse<List<GocardlessBank>> {}
