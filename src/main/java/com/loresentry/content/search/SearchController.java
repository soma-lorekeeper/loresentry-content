package com.loresentry.content.search;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/projects/{projectId}/search")
    public SearchResponses.Results search(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @RequestParam(name = "q", required = false) String query) {
        return search.search(userId, projectId, query);
    }
}
