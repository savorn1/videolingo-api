package com.example.videolingo.config;

import com.example.videolingo.entity.Language;
import com.example.videolingo.repository.LanguageRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

// Seeds a starter set of languages the first time the table is empty (English
// as the default). After that the table is managed from the admin UI and this
// never runs again — deleting a seeded language doesn't bring it back.
@Component
@RequiredArgsConstructor
@Slf4j
public class LanguageSeeder implements CommandLineRunner {

    private final LanguageRepository languageRepository;

    private static final List<String[]> STARTER = List.of(
            new String[] {"en", "English", "English"},
            new String[] {"km", "Khmer", "ភាសាខ្មែរ"},
            new String[] {"zh", "Chinese", "中文"},
            new String[] {"ja", "Japanese", "日本語"},
            new String[] {"ko", "Korean", "한국어"},
            new String[] {"th", "Thai", "ไทย"},
            new String[] {"vi", "Vietnamese", "Tiếng Việt"},
            new String[] {"fr", "French", "Français"},
            new String[] {"de", "German", "Deutsch"},
            new String[] {"es", "Spanish", "Español"},
            new String[] {"it", "Italian", "Italiano"},
            new String[] {"pt", "Portuguese", "Português"},
            new String[] {"ru", "Russian", "Русский"},
            new String[] {"ar", "Arabic", "العربية"},
            new String[] {"hi", "Hindi", "हिन्दी"},
            new String[] {"id", "Indonesian", "Bahasa Indonesia"});

    @Override
    public void run(String... args) {
        if (languageRepository.count() > 0) {
            return;
        }
        languageRepository.saveAll(STARTER.stream()
                .map(l -> Language.builder()
                        .code(l[0])
                        .name(l[1])
                        .nativeName(l[2])
                        .enabled(true)
                        .isDefault(l[0].equals("en"))
                        .build())
                .toList());
        log.info("Seeded {} starter languages (default: en)", STARTER.size());
    }
}
