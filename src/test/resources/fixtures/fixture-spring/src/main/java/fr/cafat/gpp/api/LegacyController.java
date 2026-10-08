package fr.cafat.gpp.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/legacy")
public class LegacyController {

  @GetMapping("/ping")
  @ResponseBody
  public String ping() {
    return "pong";
  }

  /** Vue HTML, pas un endpoint REST. */
  @GetMapping("/page")
  public String page() {
    return "page";
  }
}
