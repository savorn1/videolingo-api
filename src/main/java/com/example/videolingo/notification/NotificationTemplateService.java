package com.example.videolingo.notification;

import com.example.videolingo.dto.NotificationDtos.TemplateFilter;
import com.example.videolingo.dto.NotificationDtos.TemplateRequest;
import com.example.videolingo.dto.NotificationDtos.TemplateResponse;
import com.example.videolingo.dto.PageResponse;
import com.example.videolingo.entity.NotificationChannel;
import com.example.videolingo.entity.NotificationTemplate;
import com.example.videolingo.exception.AppException;
import com.example.videolingo.repository.NotificationBatchRepository;
import com.example.videolingo.repository.NotificationTemplateRepository;
import com.example.videolingo.util.PageableUtils;
import jakarta.persistence.criteria.JoinType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class NotificationTemplateService {

    private static final Set<String> SORTABLE = Set.of("name", "code", "subject", "createdAt", "updatedAt");

    private final NotificationTemplateRepository templateRepository;
    private final NotificationBatchRepository batchRepository;

    @Transactional(readOnly = true)
    public PageResponse<TemplateResponse> list(TemplateFilter f) {
        List<Specification<NotificationTemplate>> c = new ArrayList<>();
        if (f.getSearch() != null && !f.getSearch().isBlank()) {
            String p = NotificationService.likePattern(f.getSearch());
            c.add((r, q, cb) -> cb.or(cb.like(cb.lower(r.get("code")), p, '\\'), cb.like(cb.lower(r.get("name")), p, '\\'),
                    cb.like(cb.lower(r.get("subject")), p, '\\')));
        }
        if (f.getChannel() != null) {
            c.add((r, q, cb) -> {
                q.distinct(true);
                return cb.equal(r.join("defaultChannels", JoinType.INNER), f.getChannel());
            });
        }
        String sortBy = SORTABLE.contains(f.getSortBy()) ? f.getSortBy() : "name";
        return PageResponse.of(templateRepository.findAll(Specification.allOf(c),
                PageableUtils.of(f.getPage(), Math.min(Math.max(f.getSize(), 1), 100), sortBy, f.getSortOrder())).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public TemplateResponse get(Long id) {
        return toResponse(find(id));
    }

    @Transactional
    public TemplateResponse create(TemplateRequest request, String actor) {
        String code = request.getCode().trim();
        if (templateRepository.existsByCodeIgnoreCase(code)) {
            throw new AppException(HttpStatus.CONFLICT, "A template with code '" + code + "' already exists");
        }
        NotificationTemplate t = new NotificationTemplate();
        apply(t, request);
        t.setCreatedBy(actor);
        t.setUpdatedBy(actor);
        return toResponse(templateRepository.save(t));
    }

    @Transactional
    public TemplateResponse update(Long id, TemplateRequest request, String actor) {
        NotificationTemplate t = find(id);
        if (templateRepository.existsByCodeIgnoreCaseAndIdNot(request.getCode().trim(), id)) {
            throw new AppException(HttpStatus.CONFLICT, "A template with code '" + request.getCode().trim() + "' already exists");
        }
        apply(t, request);
        t.setUpdatedBy(actor);
        return toResponse(templateRepository.save(t));
    }

    /** Past sends keep a snapshot of the template's code/name, so history is unaffected. */
    @Transactional
    public void delete(Long id) {
        templateRepository.delete(find(id));
    }

    NotificationTemplate find(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Notification template not found with id: " + id));
    }

    private static void apply(NotificationTemplate t, TemplateRequest r) {
        t.setCode(r.getCode().trim());
        t.setName(r.getName().trim());
        t.setDescription(r.getDescription() == null || r.getDescription().isBlank() ? null : r.getDescription().trim());
        t.setSubject(r.getSubject().trim());
        t.setBody(r.getBody().strip());
        t.setDefaultChannels(new LinkedHashSet<>(r.getDefaultChannels()));
    }

    private TemplateResponse toResponse(NotificationTemplate t) {
        List<NotificationChannel> channels = t.getDefaultChannels().stream().sorted(Comparator.comparing(Enum::ordinal)).toList();
        return new TemplateResponse(t.getId(), t.getCode(), t.getName(), t.getDescription(), t.getSubject(), t.getBody(), channels,
                List.copyOf(TemplateRenderer.customVariables(t.getSubject(), t.getBody())), batchRepository.countByTemplateId(t.getId()),
                t.getCreatedBy(), t.getUpdatedBy(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
