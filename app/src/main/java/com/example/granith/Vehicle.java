package com.example.granith;

class Vehicle {
    private String id;
    private String codigo;
    private String descricao;
    private String status;
    private String motorista;
    // getters and setters...

    public Vehicle() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMotorista() { return motorista; }
    public void setMotorista(String motorista) { this.motorista = motorista; }
}