package iroha.validation.filter;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;

/**
 * Useful utility class to provide CORS-friendly interaction
 */
public class CrossDomainFilter implements ContainerResponseFilter {

  /**
   * {@inheritDoc}
   */
  @Override
  public void filter(ContainerRequestContext creq, ContainerResponseContext cres) {
    cres.getHeaders().add("Access-Control-Allow-Origin", "*");
    cres.getHeaders().add("Access-Control-Allow-Headers",
        "origin, content-type, accept");
    cres.getHeaders().add("Access-Control-Allow-Credentials", "true");
    cres.getHeaders().add("Access-Control-Allow-Methods",
        "GET, POST, PUT, DELETE, OPTIONS, HEAD");
  }
}
