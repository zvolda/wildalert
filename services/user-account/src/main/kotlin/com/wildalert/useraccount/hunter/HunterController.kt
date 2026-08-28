package com.wildalert.useraccount.hunter

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/hunters")
class HunterController(
    private val service: HunterService,
) {

    /** Register a new hunter. Returns 201 Created with the stored record. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody request: RegisterHunterRequest): HunterResponse =
        HunterResponse.from(service.register(request))

    /** Fetch a hunter by id. Returns 404 if it doesn't exist. */
    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): HunterResponse =
        HunterResponse.from(service.getById(id))

    /** Update phone / plan / active. Returns the updated record. */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateHunterRequest,
    ): HunterResponse =
        HunterResponse.from(service.update(id, request))
}
