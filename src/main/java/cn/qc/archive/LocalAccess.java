package cn.qc.archive;

import java.io.IOException;
import javax.servlet.*;
import javax.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LocalAccess extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
            throws IOException, ServletException {
        String host=req.getServerName(), origin=req.getHeader("Origin");
        String expected=req.getScheme()+"://"+req.getHeader("Host");
        boolean local="localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
        boolean write=!("GET".equals(req.getMethod()) || "HEAD".equals(req.getMethod()));
        if (!local || (origin!=null && !origin.equals(expected)) ||
            (write && !"local".equals(req.getHeader("X-QC-Request")))) {
            res.sendError(403);return;
        }
        res.setHeader("X-Content-Type-Options","nosniff");
        res.setHeader("X-Frame-Options","DENY");
        res.setHeader("Cache-Control","no-store");
        chain.doFilter(req,res);
    }
}
