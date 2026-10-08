package fr.cafat.gpp.api

import fr.cafat.gpp.pg.domain.Dossier
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/dossiers")
class DossierController {

    @GetMapping("/{id}")
    fun getDossier(@PathVariable id: Long): Dossier? = null
}
