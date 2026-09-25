package com.example.videolingo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LanguageRequest {

    // BCP 47: 2–3 letter language, then optional script/region subtags.
    // Case-insensitive on input; stored canonically (see LanguageServiceImpl.canonicalCode).
    @NotBlank
    @Size(max = 10)
    @Pattern(regexp = "^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$", message = "must be a language tag like \"en\", \"km\" or \"pt-BR\"")
    private String code;

    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 100)
    private String nativeName;

    // Create only — on update, enabled/default have their own endpoints.
    private Boolean enabled;
}
